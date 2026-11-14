package kr.finsight.admin;

import java.io.IOException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.content.ContentEvents;
import kr.finsight.diagnosis.YfsiScoring;
import kr.finsight.jobs.JobLeases;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminDashboardExports {
    enum Kind { DASHBOARD_AGGREGATE, CONTENT_PERFORMANCE, MASKED_USERS }
    enum Format { CSV, PDF }

    record Request(Kind kind, AdminDashboard.Filter filter, List<String> columns, Format format) {}
    record Job(UUID id, String status, Format format) {}
    record Link(String url, OffsetDateTime expiresAt) {}
    record File(Format format, byte[] bytes, Kind kind) {}
    private record Pending(UUID id, int retryCount) {}
    private record Archive(UUID owner, Kind kind, long epoch, byte[] bytes) {}
    private record Issued(OffsetDateTime expiresAt, Kind kind) {}
    private record Work(UUID id, UUID owner, Kind kind, LocalDate from, LocalDate to, String age, String pressure,
                        ContentEvents.Kind contentKind, String contentId, Integer revision, boolean combineVersions,
                        Format format, Integer userSelectionCount) {}

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AdminDashboardExports.class);
    private static final String LINK_DOMAIN = "admin-dashboard-export";
    static final String QUEUE = "ADMIN_AGGREGATE_EXPORT";
    private final JdbcClient db;
    private final AdminDashboard dashboard;
    private final AdminContentPerformance performance;
    private final AdminMutationJournal journal;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate commit;
    private final AdminExportLinks links;
    private final JobLeases leases;
    private final AdminUsers users;
    private final tools.jackson.databind.json.JsonMapper json;

    AdminDashboardExports(JdbcClient db, AdminDashboard dashboard, AdminContentPerformance performance, AdminMutationJournal journal,
                          PlatformTransactionManager transactions, AdminExportLinks links, JobLeases leases,
                          AdminUsers users, tools.jackson.databind.json.JsonMapper json) {
        this.db = db;
        this.dashboard = dashboard;
        this.performance = performance;
        this.journal = journal;
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.commit = new TransactionTemplate(transactions);
        this.links = links;
        this.leases = leases;
        this.users = users;
        this.json = json;
    }

    @Transactional
    Job requestUsers(AdminAuth.Identity actor, AdminUsers.Filters filter, List<UUID> ids, String key) {
        return requestUsers(actor, filter, ids, null, key);
    }

    @Transactional
    AdminCampaignTargetPreview.Preview previewUsers(AdminAuth.Identity actor, AdminUsers.Filters filter) {
        lockRole(actor, Kind.MASKED_USERS);
        var id = UUID.randomUUID();
        var expires = db.sql("""
                INSERT INTO admin_masked_export_preview(id,actor_id) VALUES (:id,:actor)
                RETURNING expires_at
                """).param("id", id).param("actor", actor.admin().id()).query(OffsetDateTime.class).single();
        int total = users.snapshotMaskedExportPreview(id, filter);
        int excluded = db.sql("""
                DELETE FROM admin_masked_export_preview_user t USING app_user u
                WHERE t.preview_id=:id AND t.user_id=u.id AND u.status='DATA_DELETION_PENDING'
                """).param("id", id).update();
        db.sql("UPDATE admin_masked_export_preview SET total=:total,eligible=:eligible WHERE id=:id")
                .param("id", id).param("total", total).param("eligible", total - excluded).update();
        return new AdminCampaignTargetPreview.Preview(id, total, total - excluded, excluded, expires);
    }

    @Transactional
    Job requestUsers(AdminAuth.Identity actor, AdminUsers.Filters filter, List<UUID> ids, UUID previewId, String key) {
        lockRole(actor, Kind.MASKED_USERS);
        if ((previewId == null && (filter == null) == (ids == null))
                || (previewId != null && (filter != null || ids != null)) ||
                (ids != null && (ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(java.util.Objects::isNull)
                        || ids.stream().distinct().count() != ids.size()))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var selection = previewId != null ? previewId : ids == null ? filter : ids.stream().sorted().toList();
        return journal.once(actor, "/exports/masked-users", key, selection, Job.class, () -> {
            int expected = previewId == null ? 0 : db.sql("""
                    SELECT eligible FROM admin_masked_export_preview
                    WHERE id=:id AND actor_id=:actor AND expires_at>clock_timestamp() FOR UPDATE
                    """).param("id", previewId).param("actor", actor.admin().id()).query(Integer.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            var id = UUID.randomUUID();
            var hash = AdminAuth.hash(json.writeValueAsString(selection));
            var job = db.sql("""
                    INSERT INTO admin_dashboard_export(id,owner_id,kind,date_from,date_to,format,user_selection_hash,content_id)
                    VALUES (:id,:owner,'MASKED_USERS',current_date,current_date,'CSV',:hash,:hash)
                    ON CONFLICT (owner_id,user_selection_hash) WHERE status='QUEUED' AND kind='MASKED_USERS'
                    DO UPDATE SET id=admin_dashboard_export.id RETURNING id,status,format
                    """).param("id", id).param("owner", actor.admin().id()).param("hash", hash).query(Job.class).single();
            if (!job.id().equals(id)) return job;
            int count = previewId != null ? db.sql("""
                    INSERT INTO admin_masked_export_user(export_id,user_id,data_epoch)
                    SELECT :id,u.id,u.content_event_epoch FROM admin_masked_export_preview_user t
                    JOIN app_user u ON u.id=t.user_id AND u.content_event_epoch=t.data_epoch
                    WHERE t.preview_id=:preview AND u.status<>'DATA_DELETION_PENDING' FOR SHARE OF u
                    """).param("id", id).param("preview", previewId).update() : filter != null
                    ? users.snapshotExportMatches(id, filter) : db.sql("""
                    INSERT INTO admin_masked_export_user(export_id,user_id,data_epoch)
                    SELECT :id,u.id,u.content_event_epoch FROM app_user u
                    WHERE u.status<>'DATA_DELETION_PENDING'
                    AND u.id IN (SELECT value::uuid FROM jsonb_array_elements_text(CAST(:ids AS jsonb)))
                    """).param("id", id).param("ids", json.writeValueAsString(ids)).update();
            if (count == 0 || (previewId != null && count != expected) || (ids != null && count != ids.size())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            if (previewId != null) db.sql("DELETE FROM admin_masked_export_preview WHERE id=:id").param("id", previewId).update();
            db.sql("UPDATE admin_dashboard_export SET user_selection_count=:count WHERE id=:id")
                    .param("count", count).param("id", id).update();
            journal.append(actor, auditAction(Kind.MASKED_USERS, "REQUESTED"), "JOB", id.toString(), Map.of("count", count));
            return job;
        }, ignored -> {});
    }

    @Transactional
    Job request(AdminAuth.Identity actor, Request input) {
        lockAnalyst(actor);
        if (input == null || input.kind() != Kind.DASHBOARD_AGGREGATE || input.format() == null
                || (input.columns() != null && !input.columns().equals(AdminDashboardExportDocument.COLUMNS))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var filter = AdminDashboard.Filter.resolve(input.filter());
        return queue(actor, Kind.DASHBOARD_AGGREGATE, filter.from(), filter.to(), filter.age(), filter.type(),
                null, null, null, false, input.format());
    }

    @Transactional
    Job requestPerformance(AdminAuth.Identity actor, AdminContentPerformance.Filter input, List<String> columns,
                           Format format, String key) {
        lockAnalyst(actor);
        if (format == null || (columns != null && !columns.equals(AdminContentExportDocument.COLUMNS))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var filter = AdminContentPerformance.Filter.resolve(input);
        return journal.once(actor, "/exports/content-performance", key, new Object[]{input, columns, format}, Job.class,
                () -> queue(actor, Kind.CONTENT_PERFORMANCE, filter.from(), filter.to(), null, filter.type(),
                        filter.kind(), filter.contentId(), filter.revision(), filter.combineVersions(), format),
                job -> {});
    }

    private Job queue(AdminAuth.Identity actor, Kind kind, LocalDate from, LocalDate to, String age,
                      YfsiScoring.Pressure type, ContentEvents.Kind contentKind, String contentId, Integer revision,
                      boolean combine, Format format) {
        var id = UUID.randomUUID();
        var job = db.sql("""
                INSERT INTO admin_dashboard_export(id,owner_id,kind,date_from,date_to,age,pressure_type,
                    content_kind,content_id,content_revision,combine_versions,format)
                VALUES (:id,:owner,:kind,:from,:to,:age,:type,:contentKind,:contentId,:revision,:combine,:format)
                ON CONFLICT (owner_id,kind,date_from,date_to,age,pressure_type,content_kind,content_id,content_revision,combine_versions)
                    WHERE status='QUEUED'
                DO UPDATE SET id=admin_dashboard_export.id RETURNING id,status,format
                """).param("id", id).param("owner", actor.admin().id()).param("kind", kind.name())
                .param("from", from).param("to", to).param("age", age, Types.VARCHAR)
                .param("type", type == null ? null : type.name(), Types.VARCHAR)
                .param("contentKind", contentKind == null ? null : contentKind.name(), Types.VARCHAR)
                .param("contentId", contentId, Types.VARCHAR).param("revision", revision, Types.INTEGER)
                .param("combine", combine).param("format", format.name()).query(Job.class).single();
        if (job.format() != format) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (job.id().equals(id)) journal.append(actor, auditAction(kind, "REQUESTED"), "JOB", id.toString(),
                Map.of("format", job.format()));
        return job;
    }

    private static String auditAction(Kind kind, String suffix) {
        return switch (kind) {
            case CONTENT_PERFORMANCE -> "CONTENT_PERFORMANCE_EXPORT_";
            case MASKED_USERS -> "MASKED_USERS_EXPORT_";
            case DASHBOARD_AGGREGATE -> "DASHBOARD_EXPORT_";
        } + suffix;
    }

    @Transactional
    Job cancel(AdminAuth.Identity actor, UUID id, String reason, Kind kind) {
        lockRole(actor, kind);
        if (reason == null || reason.isBlank() || reason.strip().codePointCount(0, reason.strip().length()) > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var job = db.sql("""
                UPDATE admin_dashboard_export SET status='CANCELED',completed_at=clock_timestamp(),
                    next_run_at=NULL,retryable=false,error_code=NULL
                WHERE id=:id AND owner_id=:owner AND kind=:kind AND status='QUEUED' RETURNING id,status,format
                """).param("id", id).param("owner", actor.admin().id()).param("kind", kind.name()).query(Job.class).optional();
        if (job.isEmpty()) {
            if (!db.sql("SELECT EXISTS(SELECT 1 FROM admin_dashboard_export WHERE id=:id AND owner_id=:owner AND kind=:kind)")
                    .param("id", id).param("owner", actor.admin().id()).param("kind", kind.name()).query(Boolean.class).single()) throw missing();
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        journal.append(actor, auditAction(kind, "CANCELED"), "JOB", id.toString(), Map.of("reasonLength", reason.strip().length()));
        return job.get();
    }

    @Transactional
    Job retry(AdminAuth.Identity actor, UUID id, String reason, String key) {
        lockRole(actor, ownedKind(actor, id));
        var normalized = reason == null ? "" : reason.strip();
        var length = normalized.codePointCount(0, normalized.length());
        if (length == 0 || length > 500) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        return journal.once(actor, "/jobs/" + id + "/retry", key, normalized, Job.class, () -> {
            var kind = db.sql("SELECT kind FROM admin_dashboard_export WHERE id=:id AND owner_id=:owner FOR UPDATE")
                    .param("id", id).param("owner", actor.admin().id()).query(Kind.class).optional()
                    .orElseThrow(AdminDashboardExports::missing);
            try {
                var result = db.sql("""
                        UPDATE admin_dashboard_export e SET status='QUEUED',error_code=NULL,completed_at=NULL,
                            started_at=NULL,next_run_at=NULL,retryable=false,automatic_retry_count=0,
                            retry_count=e.retry_count+1
                        WHERE e.id=:id AND e.status='FAILED' AND e.retryable
                        AND NOT EXISTS (
                            SELECT 1 FROM admin_dashboard_export queued WHERE queued.owner_id=e.owner_id
                            AND queued.kind=e.kind AND queued.status='QUEUED'
                            AND ROW(queued.date_from,queued.date_to,queued.age,queued.pressure_type,
                                    queued.content_kind,queued.content_id,queued.content_revision,queued.combine_versions)
                                IS NOT DISTINCT FROM
                                ROW(e.date_from,e.date_to,e.age,e.pressure_type,
                                    e.content_kind,e.content_id,e.content_revision,e.combine_versions)
                            AND queued.user_selection_hash IS NOT DISTINCT FROM e.user_selection_hash)
                        RETURNING id,status,format
                        """).param("id", id).query(Job.class).optional()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
                journal.append(actor, auditAction(kind, "RETRIED"), "JOB", id.toString(), Map.of("reasonLength", length));
                return result;
            } catch (DuplicateKeyException duplicate) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
        }, ignored -> {});
    }

    @Transactional
    Link issue(AdminAuth.Identity actor, UUID id) {
        lockRole(actor, ownedKind(actor, id));
        var token = links.create(LINK_DOMAIN, id, actor.admin().id());
        var issued = db.sql("""
                INSERT INTO admin_dashboard_export_link(token_hash,export_id,expires_at)
                SELECT :hash,id,least(expires_at,clock_timestamp()+interval '5 minutes')
                FROM admin_dashboard_export WHERE id=:id AND owner_id=:owner AND status='COMPLETED'
                AND archive IS NOT NULL AND expires_at>clock_timestamp()
                RETURNING expires_at,(SELECT kind FROM admin_dashboard_export WHERE id=:id) AS kind
                """).param("hash", token.hash()).param("id", id).param("owner", actor.admin().id())
                .query(Issued.class).optional().orElseThrow(AdminDashboardExports::missing);
        journal.append(actor, auditAction(issued.kind(), "LINK_ISSUED"), "JOB", id.toString(), Map.of());
        return new Link("/api/v1/admin/exports/" + id + "/file?token=" + token.value()
                + "&signature=" + token.signature(), issued.expiresAt());
    }

    @Transactional
    File download(AdminAuth.Identity actor, UUID id, String token, String signature) {
        lockRole(actor, ownedKind(actor, id));
        var hash = links.verify(LINK_DOMAIN, id, actor.admin().id(), token, signature);
        var file = db.sql("""
                SELECT e.format,e.archive,e.kind FROM admin_dashboard_export e
                JOIN admin_dashboard_export_link l ON l.export_id=e.id
                WHERE e.id=:id AND e.owner_id=:owner AND l.token_hash=:hash
                AND e.status='COMPLETED' AND e.archive IS NOT NULL
                AND e.expires_at>clock_timestamp() AND l.expires_at>clock_timestamp()
                """).param("id", id).param("owner", actor.admin().id())
                .param("hash", hash)
                .query((row, n) -> new File(Format.valueOf(row.getString(1)), row.getBytes(2), Kind.valueOf(row.getString(3))))
                .optional().orElseThrow(AdminDashboardExports::missing);
        journal.append(actor, auditAction(file.kind(), "DOWNLOADED"), "JOB", id.toString(), Map.of("format", file.format()));
        return file;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    void run() {
        var jobs = db.sql("""
                SELECT e.id,e.retry_count FROM admin_dashboard_export e
                WHERE e.status='QUEUED' AND (e.next_run_at IS NULL OR e.next_run_at<=statement_timestamp())
                    AND NOT EXISTS (SELECT 1 FROM job_execution_lease l WHERE l.queue=:queue AND l.job_id=e.id
                        AND l.expires_at>statement_timestamp())
                ORDER BY e.requested_at,e.id LIMIT 3
                """).param("queue", QUEUE).query(Pending.class).list();
        for (var job : jobs) {
            try {
                process(job);
            } catch (RuntimeException failure) {
                LOG.warn("관리자 집계 내보내기 작업 처리 실패 {} ({})", job.id(), failure.getClass().getSimpleName());
            }
        }
    }

    void process(UUID id) {
        db.sql("""
                SELECT id,retry_count FROM admin_dashboard_export WHERE id=:id AND status='QUEUED'
                    AND (next_run_at IS NULL OR next_run_at<=statement_timestamp())
                """).param("id", id).query(Pending.class).optional().ifPresent(this::process);
    }

    private void process(Pending job) {
        var acquired = leases.acquire(QUEUE, job.id());
        if (acquired.isEmpty()) return;
        var claim = acquired.get();
        var heartbeat = leases.keepAlive(claim);
        try {
            var archive = snapshot.execute(status -> generate(job));
            if (archive != null) commit.executeWithoutResult(status -> complete(job, claim, archive));
        } catch (ConcurrencyFailureException concurrentChange) {
            // The next poll takes a fresh snapshot.
        } catch (RuntimeException failure) {
            LOG.warn("관리자 집계 내보내기 생성 실패 {} ({})", job.id(), failure.getClass().getSimpleName());
            boolean retryable = failure instanceof java.io.UncheckedIOException || failure instanceof TransientDataAccessException
                    || failure instanceof DataAccessResourceFailureException || failure instanceof CannotCreateTransactionException;
            commit.executeWithoutResult(status -> {
                if (leases.lockOwned(claim)) recordFailure(job.id(), job.retryCount(), retryable);
            });
        } finally {
            heartbeat.cancel(false);
            leases.release(claim);
        }
    }

    void recordFailure(UUID id, int retryCount, boolean retryable) {
        db.sql("""
                UPDATE admin_dashboard_export SET
                    status=CASE WHEN :retryable AND automatic_retry_count<3 THEN 'QUEUED' ELSE 'FAILED' END,
                    next_run_at=CASE WHEN :retryable AND automatic_retry_count<3 THEN clock_timestamp()
                        + CASE automatic_retry_count
                            WHEN 0 THEN interval '1 minute' WHEN 1 THEN interval '5 minutes' ELSE interval '15 minutes'
                          END END,
                    retry_count=retry_count+CASE WHEN :retryable AND automatic_retry_count<3 THEN 1 ELSE 0 END,
                    automatic_retry_count=automatic_retry_count+
                        CASE WHEN :retryable AND automatic_retry_count<3 THEN 1 ELSE 0 END,
                    retryable=:retryable,
                    completed_at=CASE WHEN NOT :retryable OR automatic_retry_count>=3 THEN clock_timestamp() END,
                    error_code='EXPORT_BUILD_FAILED'
                WHERE id=:id AND status='QUEUED' AND retry_count=:retryCount
                    AND (next_run_at IS NULL OR next_run_at<=clock_timestamp())
                """).param("id", id).param("retryCount", retryCount).param("retryable", retryable).update();
    }

    private Archive generate(Pending pending) {
        var epoch = db.sql("SELECT version FROM admin_dashboard_export_epoch WHERE singleton").query(Long.class).single();
        var job = db.sql("""
                SELECT e.id,owner_id AS owner,kind,date_from AS "from",date_to AS "to",age,pressure_type AS pressure,
                    content_kind,content_id,content_revision AS revision,combine_versions,format,user_selection_count
                FROM admin_dashboard_export e JOIN admin_account a ON a.id=e.owner_id AND a.status='ACTIVE'
                JOIN admin_role r ON r.admin_id=a.id AND r.role=CASE WHEN e.kind='MASKED_USERS' THEN 'OPERATOR' ELSE 'ANALYST' END
                WHERE e.id=:id AND e.status='QUEUED' AND e.retry_count=:retry
                    AND (e.next_run_at IS NULL OR e.next_run_at<=statement_timestamp())
                """).param("id", pending.id()).param("retry", pending.retryCount()).query(Work.class).optional();
        if (job.isEmpty()) return null;
        var work = job.get();
        var pressure = work.pressure() == null ? null : YfsiScoring.Pressure.valueOf(work.pressure());
        byte[] content;
        try {
            if (work.kind() == Kind.MASKED_USERS) {
                var rows = users.exportRows(work.id());
                if (rows.size() != work.userSelectionCount()) throw new ResponseStatusException(HttpStatus.CONFLICT);
                var csv = new StringBuilder("\uFEFF사용자 ID,마스킹 이메일,가입일,최근 진단일,대표 유형,진단 횟수,계정 상태,태그\r\n");
                for (var row : rows) {
                    var fields = List.of(row.id().toString(), row.maskedEmail(), row.createdAt().toString(),
                            java.util.Objects.toString(row.lastDiagnosisAt(), ""), java.util.Objects.toString(row.representativeType(), ""),
                            Long.toString(row.diagnosisCount()), row.status().name(), String.join(", ", row.tags()));
                    csv.append(fields.stream().map(kr.finsight.Csv::safeCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
                }
                content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else if (work.kind() == Kind.CONTENT_PERFORMANCE) {
                var filter = new AdminContentPerformance.Filter(work.from(), work.to(), work.contentKind(), pressure,
                        work.contentId(), work.revision(), work.combineVersions());
                var report = new AdminContentExportDocument(filter, performance.load(filter), work.owner());
                content = work.format() == Format.PDF ? report.pdf() : report.csv();
            } else {
                var data = dashboard.load(work.from(), work.to(), work.age(), pressure);
                var report = new AdminDashboardExportDocument(
                        new AdminDashboard.Filter(work.from(), work.to(), work.age(), pressure), data, work.owner());
                content = work.format() == Format.PDF ? report.pdf() : report.csv();
            }
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        return new Archive(work.owner(), work.kind(), epoch, content);
    }

    private void complete(Pending job, JobLeases.Claim claim, Archive archive) {
        if (!leases.lockOwned(claim) || !activeRole(archive.owner(), archive.kind() == Kind.MASKED_USERS ? "OPERATOR" : "ANALYST")) return;
        long epoch = db.sql("SELECT version FROM admin_dashboard_export_epoch WHERE singleton FOR SHARE").query(Long.class).single();
        if (epoch != archive.epoch()) return;
        db.sql("""
                UPDATE admin_dashboard_export SET status='COMPLETED',started_at=coalesce(started_at,:start),
                    error_code=NULL,retryable=false,next_run_at=NULL,
                    completed_at=clock_timestamp(),expires_at=clock_timestamp()+interval '168 hours',archive=:file
                WHERE id=:id AND status='QUEUED' AND retry_count=:retry
                """).param("id", job.id()).param("retry", job.retryCount()).param("start", claim.claimedAt())
                .param("file", archive.bytes()).update();
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void expire() {
        db.sql("DELETE FROM admin_masked_export_preview WHERE expires_at<=clock_timestamp()").update();
        db.sql("DELETE FROM admin_dashboard_export_link WHERE expires_at<=clock_timestamp()").update();
        db.sql("UPDATE admin_dashboard_export SET archive=NULL WHERE archive IS NOT NULL AND expires_at<=clock_timestamp()")
                .update();
        db.sql("""
                DELETE FROM admin_masked_export_user u USING admin_dashboard_export e
                WHERE u.export_id=e.id AND (e.status IN ('COMPLETED','CANCELED') OR (e.status='FAILED' AND NOT e.retryable))
                """).update();
    }

    private void lockAnalyst(AdminAuth.Identity actor) {
        lockRole(actor, Kind.DASHBOARD_AGGREGATE);
    }

    private Kind ownedKind(AdminAuth.Identity actor, UUID id) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!actor.permissions().contains("ANALYST") && !actor.permissions().contains("OPERATOR")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return db.sql("SELECT kind FROM admin_dashboard_export WHERE id=:id AND owner_id=:owner")
                .param("id", id).param("owner", actor.admin().id()).query(Kind.class).optional()
                .orElseThrow(AdminDashboardExports::missing);
    }

    private void lockRole(AdminAuth.Identity actor, Kind kind) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var role = kind == Kind.MASKED_USERS ? "OPERATOR" : "ANALYST";
        if (!actor.permissions().contains(role) || !activeRole(actor.admin().id(), role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private boolean activeRole(UUID actorId, String role) {
        return db.sql("""
                SELECT a.id FROM admin_account a JOIN admin_role r ON r.admin_id=a.id
                WHERE a.id=:id AND a.status='ACTIVE' AND r.role=:role FOR SHARE OF a,r
                """).param("id", actorId).param("role", role).query(UUID.class).optional().isPresent();
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
