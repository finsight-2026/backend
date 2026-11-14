package kr.finsight.comparison;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.finsight.diagnosis.YfsiScoring;
import kr.finsight.jobs.JobLeases;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
public class ComparisonBuild {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ComparisonBuild.class);
    static final String QUEUE = "COMPARISON_BUILD";
    public enum Status { BUILDING, READY, FAILED, IN_REVIEW, APPROVED, INVALIDATED }

    public record Snapshot(UUID id, String type, LocalDate from, LocalDate to, String formulaVersion,
                           long datasetVersion, int sampleSize, int excludedSize, JsonNode metrics,
                           Status status, long version, OffsetDateTime builtAt, String failureReason,
                           int retryCount, OffsetDateTime nextRunAt) {}
    private record Pending(UUID id, String type, LocalDate from, LocalDate to, String formulaVersion,
                           long datasetVersion, int retryCount) {}
    private record Dataset(long version, Integer minSample) {}
    private static final String SNAPSHOT_COLUMNS = """
            id,type,period_start,period_end,formula_version,dataset_version,sample_n,excluded_n,
            metrics,status,version,built_at,failure_reason,retry_count,next_run_at
            """;

    private final JdbcClient db;
    private final JsonMapper json;
    private final JobLeases leases;
    private final TransactionTemplate transactions;

    ComparisonBuild(JdbcClient db, JsonMapper json, JobLeases leases, PlatformTransactionManager transactions) {
        this.db = db;
        this.json = json;
        this.leases = leases;
        this.transactions = new TransactionTemplate(transactions);
    }

    @Transactional
    public Snapshot request(UUID actor, YfsiScoring.Pressure type, LocalDate from, LocalDate to, String formulaVersion) {
        var today = LocalDate.now(KST);
        var end = to == null ? today : to;
        var start = from == null ? end.minusDays(29) : from;
        if (end.isAfter(today) || start.isAfter(end) || end.isAfter(start.plusDays(365))
                || !YfsiScoring.FORMULA_VERSION.equals(formulaVersion)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        long datasetVersion = lockDataset(type.name()).version();
        var existing = db.sql("""
                SELECT id FROM comparison_snapshot WHERE type = :type AND period_start = :start AND period_end = :end
                    AND formula_version = :formula AND dataset_version = :dataset AND status = 'BUILDING'
                """).param("type", type.name()).param("start", start).param("end", end)
                .param("formula", formulaVersion).param("dataset", datasetVersion).query(UUID.class).optional();
        if (existing.isPresent()) return get(existing.get());
        var id = UUID.randomUUID();
        db.sql("""
                INSERT INTO comparison_snapshot
                    (id,type,period_start,period_end,formula_version,dataset_version,sample_n,metrics,status,created_by)
                VALUES (:id,:type,:start,:end,:formula,:dataset,0,'{}','BUILDING',:actor)
                """).param("id", id).param("type", type.name()).param("start", start).param("end", end)
                .param("formula", formulaVersion).param("dataset", datasetVersion).param("actor", actor).update();
        return get(id);
    }

    public List<Snapshot> list(YfsiScoring.Pressure type, Status status) {
        var sql = "SELECT " + SNAPSHOT_COLUMNS + " FROM comparison_snapshot WHERE true"
                + (type == null ? "" : " AND type = :type") + (status == null ? "" : " AND status = :status")
                + " ORDER BY built_at DESC,id DESC LIMIT 100";
        var query = db.sql(sql);
        if (type != null) query.param("type", type.name());
        if (status != null) query.param("status", status.name());
        return query.query(this::snapshot).list();
    }

    public Snapshot get(UUID id) {
        return db.sql("SELECT " + SNAPSHOT_COLUMNS + " FROM comparison_snapshot WHERE id = :id")
                .param("id", id).query(this::snapshot).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Snapshot submit(UUID id, long expectedVersion) {
        var initial = get(id);
        if (lockDataset(initial.type()).version() != initial.datasetVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        var updated = db.sql("""
                UPDATE comparison_snapshot SET status = 'IN_REVIEW', version = version + 1
                WHERE id = :id AND version = :version AND status = 'READY'
                """).param("id", id).param("version", expectedVersion).update();
        var snapshot = get(id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return snapshot;
    }

    boolean processNext() {
        var next = db.sql("""
                SELECT s.id,s.type,s.period_start AS "from",s.period_end AS "to",s.formula_version,s.dataset_version,s.retry_count
                FROM comparison_snapshot s WHERE s.status='BUILDING'
                    AND (s.next_run_at IS NULL OR s.next_run_at<=statement_timestamp())
                    AND NOT EXISTS(SELECT 1 FROM job_execution_lease l WHERE l.queue=:queue AND l.job_id=s.id
                        AND l.expires_at>statement_timestamp())
                ORDER BY s.built_at,s.id LIMIT 1
                """).param("queue", QUEUE).query(Pending.class).optional();
        if (next.isEmpty()) return false;
        var work = next.get();
        var acquired = leases.acquire(QUEUE, work.id());
        if (acquired.isEmpty()) return false;
        var claim = acquired.get();
        var heartbeat = leases.keepAlive(claim);
        try {
            transactions.executeWithoutResult(status -> process(work, claim, status));
        } catch (RuntimeException failure) {
            LOG.warn("익명 비교 집계 실패 {} ({})", work.id(), failure.getClass().getSimpleName());
            boolean retryable = failure instanceof TransientDataAccessException
                    || failure instanceof DataAccessResourceFailureException || failure instanceof CannotCreateTransactionException;
            transactions.executeWithoutResult(status -> {
                if (leases.lockOwned(claim)) recordFailure(work, retryable);
            });
        } finally {
            heartbeat.cancel(false);
            leases.release(claim);
        }
        return true;
    }

    private void process(Pending work, JobLeases.Claim claim, TransactionStatus transaction) {
        if (!db.sql("""
                SELECT EXISTS(SELECT 1 FROM comparison_snapshot WHERE id=:id AND status='BUILDING'
                    AND retry_count=:retry AND (next_run_at IS NULL OR next_run_at<=statement_timestamp()))
                """).param("id", work.id()).param("retry", work.retryCount()).query(Boolean.class).single()) return;
        long source = db.sql("SELECT version FROM comparison_dataset WHERE type=:type")
                .param("type", work.type()).query(Long.class).single();
        if (source != work.datasetVersion()) {
            if (leases.lockOwned(claim)) sourceChanged(work);
            return;
        }
        int excluded = db.sql("""
                WITH latest AS (
                    SELECT DISTINCT ON (a.user_id) d.representative_type, a.formula_version, u.status,
                        u.comparison_test_account
                    FROM survey_attempt a JOIN diagnosis d ON d.attempt_id = a.id
                    JOIN app_user u ON u.id = a.user_id
                    WHERE d.completed_at >= :start AND d.completed_at < :end
                    ORDER BY a.user_id,d.completed_at DESC,d.id DESC
                )
                SELECT count(*) FROM latest WHERE representative_type = :type
                    AND (status <> 'ACTIVE' OR comparison_test_account OR formula_version <> :formula)
                """).param("type", work.type()).param("formula", work.formulaVersion())
                .param("start", work.from().atStartOfDay(KST).toOffsetDateTime())
                .param("end", work.to().plusDays(1).atStartOfDay(KST).toOffsetDateTime()).query(Integer.class).single();
        db.sql("""
                WITH latest AS (
                    SELECT DISTINCT ON (a.user_id) a.user_id, d.id, d.representative_type, a.formula_version
                    FROM survey_attempt a JOIN diagnosis d ON d.attempt_id = a.id
                    JOIN app_user u ON u.id = a.user_id
                    WHERE u.status = 'ACTIVE' AND NOT u.comparison_test_account
                        AND d.completed_at >= :start AND d.completed_at < :end
                    ORDER BY a.user_id,d.completed_at DESC,d.id DESC
                )
                INSERT INTO comparison_snapshot_member(snapshot_id,user_id,diagnosis_id)
                SELECT :id,user_id,latest.id FROM latest WHERE representative_type = :type AND formula_version = :formula
                """).param("id", work.id()).param("type", work.type()).param("formula", work.formulaVersion())
                .param("start", work.from().atStartOfDay(KST).toOffsetDateTime())
                .param("end", work.to().plusDays(1).atStartOfDay(KST).toOffsetDateTime()).update();

        var metrics = db.sql("""
                WITH scores AS (
                    SELECT d.score_snapshot s FROM comparison_snapshot_member m
                    JOIN diagnosis d ON d.id = m.diagnosis_id WHERE m.snapshot_id = :id
                ), cells AS (
                    SELECT 'YFSI_MEAN' AS metric, '' AS area, s -> 'total' AS value FROM scores
                    UNION ALL
                    SELECT 'PERCEIVED_STRESS_MEAN', '', s -> 'perceivedStress' FROM scores
                    UNION ALL
                    SELECT 'EXTERNAL_AREA_MEAN', element ->> 'pressure', element -> 'score'
                        FROM scores CROSS JOIN LATERAL jsonb_array_elements(s -> 'rankedPressures') element
                    UNION ALL
                    SELECT 'RESOURCE_DOMAIN_MEAN', element ->> 'resource', element -> 'score'
                        FROM scores CROSS JOIN LATERAL jsonb_array_elements(s -> 'resources') element
                ), means AS (
                    SELECT metric,area,jsonb_build_object('n',count(*),
                        'mean',round(avg((value ->> 'numerator')::numeric / (value ->> 'denominator')::numeric),2)) cell
                    FROM cells WHERE (value ->> 'denominator')::numeric > 0 GROUP BY metric,area
                ), metrics AS (
                    SELECT metric,cell AS value FROM means WHERE area = ''
                    UNION ALL
                    SELECT metric,jsonb_object_agg(area,cell) FROM means WHERE area <> '' GROUP BY metric
                )
                SELECT (SELECT count(*) FROM scores) AS sample_size,
                    coalesce((SELECT jsonb_object_agg(metric,value) FROM metrics),'{}') AS metrics
                """).param("id", work.id())
                .query((row, index) -> new Metrics(row.getInt(1), row.getString(2))).single();

        long currentVersion = lockDataset(work.type()).version();
        if (!leases.lockOwned(claim) || get(work.id()).status() != Status.BUILDING) {
            transaction.setRollbackOnly();
            return;
        }
        if (currentVersion != work.datasetVersion()) {
            db.sql("DELETE FROM comparison_snapshot_member WHERE snapshot_id=:id").param("id", work.id()).update();
            sourceChanged(work);
            return;
        }
        db.sql("""
                UPDATE comparison_snapshot SET status='READY',version=version+1,built_at=clock_timestamp(),
                    sample_n=:size,excluded_n=:excluded,metrics=CAST(:metrics AS jsonb),next_run_at=NULL
                WHERE id=:id AND status='BUILDING'
                """).param("id", work.id()).param("size", metrics.sampleSize()).param("excluded", excluded)
                .param("metrics", metrics.json()).update();
    }

    private record Metrics(int sampleSize, String json) {}

    private void sourceChanged(Pending work) {
        db.sql("""
                UPDATE comparison_snapshot SET status='FAILED',version=version+1,failure_reason='SOURCE_CHANGED',next_run_at=NULL
                WHERE id=:id AND status='BUILDING'
                """).param("id", work.id()).update();
    }

    private void recordFailure(Pending work, boolean retryable) {
        db.sql("""
                UPDATE comparison_snapshot SET
                    status=CASE WHEN :retryable AND retry_count<3 THEN 'BUILDING' ELSE 'FAILED' END,
                    next_run_at=CASE WHEN :retryable AND retry_count<3 THEN clock_timestamp()+CASE retry_count
                        WHEN 0 THEN interval '1 minute' WHEN 1 THEN interval '5 minutes' ELSE interval '15 minutes' END END,
                    retry_count=retry_count+CASE WHEN :retryable AND retry_count<3 THEN 1 ELSE 0 END,
                    version=version+1,
                    failure_reason=CASE WHEN NOT :retryable OR retry_count>=3 THEN 'BUILD_FAILED' END
                WHERE id=:id AND status='BUILDING' AND retry_count=:retry
                """).param("id", work.id()).param("retry", work.retryCount()).param("retryable", retryable).update();
    }

    private Dataset lockDataset(String type) {
        return db.sql("SELECT version,min_sample FROM comparison_dataset WHERE type = :type FOR UPDATE")
                .param("type", type).query(Dataset.class).single();
    }

    private Snapshot snapshot(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Snapshot(row.getObject("id", UUID.class), row.getString("type"),
                row.getObject("period_start", LocalDate.class), row.getObject("period_end", LocalDate.class),
                row.getString("formula_version"), row.getLong("dataset_version"), row.getInt("sample_n"),
                row.getInt("excluded_n"), json.readTree(row.getString("metrics")), Status.valueOf(row.getString("status")),
                row.getLong("version"), row.getObject("built_at", OffsetDateTime.class), row.getString("failure_reason"),
                row.getInt("retry_count"), row.getObject("next_run_at", OffsetDateTime.class));
    }
}
