package kr.finsight.admin;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.Csv;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminPiiExports {
    enum Scope { PROFILE, ANSWERS }
    record Filter(UUID userId, UUID diagnosisId, Scope scope) {}
    private record Source(UUID id, UUID owner, UUID userId, UUID diagnosisId, UUID grantId, Scope scope, String sessionHash) {}

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AdminPiiExports.class);
    private static final String LINK_DOMAIN = "admin-pii-export";
    private static final String JOB_FIELDS = "id,status,'CSV' AS format";
    private static final List<String> PROFILE_COLUMNS = List.of("field", "value");
    private static final List<String> ANSWER_COLUMNS = List.of("questionNo", "response");
    private static final List<String> PROFILE_FIELDS = List.of("age", "situation", "situationOther", "gender",
            "housing", "housingOther", "income", "incomeOther", "debt", "debtOther", "debtType", "debtTypeOther", "expenses");
    private static final String SOURCE = """
            SELECT j.id,j.owner_id AS owner,s.user_id,s.diagnosis_id,s.grant_id,j.scope,g.session_hash
            FROM admin_pii_export j JOIN admin_pii_export_source s ON s.export_id=j.id
            JOIN pii_grant g ON g.id=s.grant_id WHERE j.id=:id
            """;

    private final JdbcClient db;
    private final AdminPii pii;
    private final AdminExportLinks links;
    private final AdminMutationJournal journal;
    private final TransactionTemplate transactions;

    AdminPiiExports(JdbcClient db, AdminPii pii, AdminExportLinks links, AdminMutationJournal journal,
                    TransactionTemplate transactions) {
        this.db = db;
        this.pii = pii;
        this.links = links;
        this.journal = journal;
        this.transactions = transactions;
    }

    @Transactional
    AdminDashboardExports.Job request(AdminAuth.Identity actor, String session, Filter filter, UUID grant,
                                      String input, List<String> columns, AdminDashboardExports.Format format) {
        var owner = privacy(actor);
        var reason = input == null ? "" : input.strip();
        int length = reason.codePointCount(0, reason.length());
        if (filter == null || filter.userId() == null || filter.scope() == null || grant == null
                || format != AdminDashboardExports.Format.CSV || length < 10 || length > 500
                || (filter.scope() == Scope.ANSWERS) != (filter.diagnosisId() != null)
                || (columns != null && !columns.equals(filter.scope() == Scope.PROFILE ? PROFILE_COLUMNS : ANSWER_COLUMNS))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        pii.authorizeExport(owner, AdminAuth.hash(session), filter.userId(), grant,
                AdminPii.Scope.valueOf(filter.scope().name()), filter.diagnosisId(), true);
        var lock = "pii-export:" + owner + ":" + filter.userId() + ":" + filter.scope() + ":" + filter.diagnosisId();
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", lock).query((row, n) -> true).single();
        var queued = db.sql("""
                SELECT j.id,s.grant_id FROM admin_pii_export j JOIN admin_pii_export_source s ON s.export_id=j.id
                WHERE j.owner_id=:owner AND s.user_id=:user AND j.scope=:scope AND j.status='QUEUED'
                AND s.diagnosis_id IS NOT DISTINCT FROM CAST(:diagnosis AS uuid)
                """).param("owner", owner).param("user", filter.userId()).param("scope", filter.scope().name())
                .param("diagnosis", filter.diagnosisId(), Types.OTHER)
                .query((row, n) -> Map.entry(row.getObject(1, UUID.class), row.getObject(2, UUID.class))).optional();
        if (queued.isPresent()) {
            var existing = queued.get();
            if (!grant.equals(existing.getValue())) throw new ResponseStatusException(HttpStatus.CONFLICT);
            return new AdminDashboardExports.Job(existing.getKey(), "QUEUED", format);
        }
        var id = UUID.randomUUID();
        db.sql("INSERT INTO admin_pii_export(id,owner_id,scope) VALUES (:id,:owner,:scope)")
                .param("id", id).param("owner", owner).param("scope", filter.scope().name()).update();
        db.sql("""
                INSERT INTO admin_pii_export_source(export_id,grant_id,user_id,diagnosis_id,reason_encrypted)
                VALUES (:id,:grant,:user,:diagnosis,:reason)
                """).param("id", id).param("grant", grant).param("user", filter.userId())
                .param("diagnosis", filter.diagnosisId(), Types.OTHER)
                .param("reason", pii.protectExport(reason.getBytes(StandardCharsets.UTF_8))).update();
        journal.append(actor, "PII_EXPORT_REQUESTED", "JOB", id.toString(), Map.of("scope", filter.scope()));
        return new AdminDashboardExports.Job(id, "QUEUED", format);
    }

    @Transactional
    AdminDashboardExports.Link issue(AdminAuth.Identity actor, String session, UUID id, UUID grant) {
        var owner = privacy(actor);
        var source = source(id, owner, grant);
        var grantExpires = authorize(source, AdminAuth.hash(session), false);
        var token = links.create(LINK_DOMAIN, id, owner);
        var expires = db.sql("""
                INSERT INTO admin_pii_export_link(token_hash,export_id,expires_at)
                SELECT :hash,j.id,least(j.expires_at,:grant,clock_timestamp()+interval '5 minutes')
                FROM admin_pii_export j JOIN admin_pii_export_source s ON s.export_id=j.id
                WHERE j.id=:id AND j.status='COMPLETED' AND s.archive_encrypted IS NOT NULL
                AND j.expires_at>clock_timestamp() AND :grant>clock_timestamp()
                RETURNING expires_at
                """).param("hash", token.hash()).param("grant", grantExpires).param("id", id)
                .query(java.time.OffsetDateTime.class).optional().orElseThrow(AdminPiiExports::missing);
        journal.append(actor, "PII_EXPORT_LINK_ISSUED", "JOB", id.toString(), Map.of("scope", source.scope()));
        return new AdminDashboardExports.Link("/api/v1/admin/exports/" + id + "/file?pii=true&token=" + token.value()
                + "&signature=" + token.signature(), expires);
    }

    @Transactional
    byte[] download(AdminAuth.Identity actor, String session, UUID id, String token, String signature) {
        var owner = privacy(actor);
        var hash = links.verify(LINK_DOMAIN, id, owner, token, signature);
        var source = source(id, owner, null);
        authorize(source, AdminAuth.hash(session), false);
        var bytes = db.sql("""
                SELECT s.archive_encrypted FROM admin_pii_export j
                JOIN admin_pii_export_source s ON s.export_id=j.id
                JOIN admin_pii_export_link l ON l.export_id=j.id
                JOIN pii_grant g ON g.id=s.grant_id
                WHERE j.id=:id AND l.token_hash=:hash AND j.status='COMPLETED'
                AND s.archive_encrypted IS NOT NULL AND l.expires_at>clock_timestamp()
                AND j.expires_at>clock_timestamp() AND g.expires_at>clock_timestamp()
                """).param("id", id).param("hash", hash).query((row, n) -> row.getBytes(1)).optional()
                .orElseThrow(AdminPiiExports::missing);
        journal.append(actor, "PII_EXPORT_DOWNLOADED", "JOB", id.toString(), Map.of("scope", source.scope()));
        return pii.openExport(bytes);
    }

    @Transactional
    AdminDashboardExports.Job cancel(AdminAuth.Identity actor, String session, UUID id, String reason) {
        var owner = privacy(actor);
        pii.authorize(owner, AdminAuth.hash(session), false);
        if (reason == null || reason.isBlank() || reason.strip().codePointCount(0, reason.strip().length()) > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        db.sql("""
                SELECT s.export_id FROM admin_pii_export_source s JOIN admin_pii_export j ON j.id=s.export_id
                WHERE j.id=:id AND j.owner_id=:owner FOR UPDATE OF s
                """).param("id", id).param("owner", owner).query(UUID.class).optional().orElseThrow(AdminPiiExports::missing);
        var job = db.sql("""
                UPDATE admin_pii_export SET status='CANCELED',completed_at=clock_timestamp()
                WHERE id=:id AND owner_id=:owner AND status='QUEUED' RETURNING
                """ + JOB_FIELDS).param("id", id).param("owner", owner).query(AdminDashboardExports.Job.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        deleteSource(id);
        journal.append(actor, "PII_EXPORT_CANCELED", "JOB", id.toString(), Map.of("reasonLength", reason.strip().length()));
        return job;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    void run() {
        var ids = db.sql("SELECT id FROM admin_pii_export WHERE status='QUEUED' ORDER BY requested_at,id LIMIT 5")
                .query(UUID.class).list();
        for (var id : ids) {
            try {
                transactions.executeWithoutResult(status -> process(id));
            } catch (ConcurrencyFailureException concurrentChange) {
                // Re-read current source and permissions on the next scheduled pass.
            } catch (RuntimeException failure) {
                if (!(failure instanceof ResponseStatusException)) {
                    LOG.warn("개인정보 내보내기 실패 {} ({})", id, failure.getClass().getSimpleName());
                }
                transactions.executeWithoutResult(status -> {
                    if (lockQueuedSource(id)) {
                        db.sql("""
                                UPDATE admin_pii_export SET status='FAILED',completed_at=clock_timestamp(),error_code=:error
                                WHERE id=:id AND status='QUEUED'
                                """).param("error", failure instanceof ResponseStatusException ? "ACCESS_ENDED" : "EXPORT_BUILD_FAILED")
                                .param("id", id).update();
                        deleteSource(id);
                    }
                });
            }
        }
    }

    void process(UUID id) {
        var source = db.sql(SOURCE + " AND j.status='QUEUED'").param("id", id).query(Source.class).optional();
        if (source.isEmpty()) return;
        var work = source.get();
        var grantExpires = authorize(work, work.sessionHash(), false);
        if (!lockQueuedSource(id)) return;
        var started = db.sql("SELECT clock_timestamp()").query(java.time.OffsetDateTime.class).single();
        var output = new StringBuilder("\uFEFF");
        if (work.scope() == Scope.PROFILE) {
            row(output, "항목", "값");
            var profile = pii.profile(work.userId());
            row(output, "email", profile.email());
            for (var field : PROFILE_FIELDS) {
                var value = profile.attributes().path(field);
                if (value.isString()) row(output, field, value.asString());
            }
        } else {
            row(output, "문항 번호", "응답값");
            for (var answer : pii.answers(work.diagnosisId())) {
                row(output, Integer.toString(answer.questionNo()), Integer.toString(answer.response()));
            }
        }
        int saved = db.sql("""
                UPDATE admin_pii_export_source SET archive_encrypted=:file
                WHERE export_id=:id AND :grantExpires>clock_timestamp()
                """).param("file", pii.protectExport(output.toString().getBytes(StandardCharsets.UTF_8)))
                .param("id", id).param("grantExpires", grantExpires).update();
        if (saved == 0) {
            deleteSource(id);
            return;
        }
        db.sql("""
                UPDATE admin_pii_export SET status='COMPLETED',started_at=:start,completed_at=clock_timestamp(),
                    expires_at=clock_timestamp()+interval '24 hours' WHERE id=:id
                """).param("start", started).param("id", id).update();
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    void expire() {
        db.sql("DELETE FROM admin_pii_export_link WHERE expires_at<=clock_timestamp()").update();
        db.sql("""
                DELETE FROM admin_pii_export_source s USING admin_pii_export j,pii_grant g
                WHERE j.id=s.export_id AND g.id=s.grant_id
                AND (j.expires_at<=clock_timestamp() OR g.expires_at<=clock_timestamp())
                """).update();
    }

    private Source source(UUID id, UUID owner, UUID grant) {
        return db.sql(SOURCE + " AND j.owner_id=:owner AND (CAST(:grant AS uuid) IS NULL OR g.id=:grant)")
                .param("id", id).param("owner", owner).param("grant", grant, Types.OTHER)
                .query(Source.class).optional().orElseThrow(AdminPiiExports::missing);
    }

    private java.time.OffsetDateTime authorize(Source source, String sessionHash, boolean recent) {
        return pii.authorizeExport(source.owner(), sessionHash, source.userId(), source.grantId(),
                AdminPii.Scope.valueOf(source.scope().name()), source.diagnosisId(), recent);
    }

    private boolean lockQueuedSource(UUID id) {
        return db.sql("""
                SELECT s.export_id FROM admin_pii_export_source s JOIN admin_pii_export j ON j.id=s.export_id
                WHERE s.export_id=:id AND j.status='QUEUED' FOR UPDATE OF s SKIP LOCKED
                """).param("id", id).query(UUID.class).optional().isPresent();
    }

    private void deleteSource(UUID id) {
        db.sql("DELETE FROM admin_pii_export_source WHERE export_id=:id").param("id", id).update();
    }

    private static void row(StringBuilder output, String name, String value) {
        output.append(Csv.safeCell(name)).append(',').append(Csv.safeCell(value)).append("\r\n");
    }

    private static UUID privacy(AdminAuth.Identity actor) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!actor.permissions().contains("PRIVACY")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor.admin().id();
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
