package kr.finsight.admin;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminJobs {
    enum Kind { CAMPAIGN, BULK_TAG, APP_EXPORT, DATA_DELETION, ACCOUNT_WITHDRAWAL, DASHBOARD_AGGREGATE, CONTENT_PERFORMANCE, PII_RAW }
    enum Status { PENDING, QUEUED, RUNNING, SCHEDULED, SENDING, COMPLETED, FAILED, PARTIAL_FAILED, CANCELED, CANCEL_REQUESTED }

    record Job(UUID id, Kind kind, Status status, UUID actorId, String scope, OffsetDateTime requestedAt,
               OffsetDateTime startedAt, OffsetDateTime completedAt, OffsetDateTime expiresAt, OffsetDateTime dueAt,
               long processed, long total, int retryCount, int requeueCount, boolean retryable, OffsetDateTime nextRunAt,
               String errorCode, boolean overdue, boolean deadlineWarning, String stage) {}
    record Page(List<Job> items, long total, int page, int size) {}
    record Filter(Kind kind, Status status, UUID actorId, LocalDate from, LocalDate to, int page, int size) {}
    record Requeue(int expectedRequeueCount, String reason) {}
    private static final String DELETION_ACCOUNT = """
            (%2$s='DATA_DELETION' AND u.status='DATA_DELETION_PENDING' AND EXISTS(SELECT 1 FROM app_data_deletion r
                WHERE r.user_id=u.id AND r.operation_id=%1$s AND r.completed_at IS NULL))
            OR (%2$s='ACCOUNT_WITHDRAWAL' AND u.status='WITHDRAWAL_PENDING' AND u.withdrawal_request_id=%1$s
                AND u.withdrawal_due_at<=statement_timestamp())
            """;

    private static final String VISIBLE = """
            FROM (
                SELECT o.id,o.kind,CASE WHEN l.job_id IS NOT NULL THEN 'RUNNING' ELSE o.status END AS status,
                    o.actor_id,o.scope,o.requested_at,coalesce(o.started_at,l.claimed_at) AS started_at,
                    o.completed_at,o.expires_at,o.due_at,o.processed,o.total,o.error_code
                FROM admin_operation_monitor o LEFT JOIN job_execution_lease l
                    ON o.kind IN ('DASHBOARD_AGGREGATE','CONTENT_PERFORMANCE') AND o.status='QUEUED'
                    AND l.queue='ADMIN_AGGREGATE_EXPORT' AND l.job_id=o.id AND l.expires_at>statement_timestamp()
            ) m
            LEFT JOIN (SELECT id AS export_id,retry_count,retryable,next_run_at FROM admin_dashboard_export) a
                ON a.export_id=m.id AND m.kind IN ('DASHBOARD_AGGREGATE','CONTENT_PERFORMANCE')
            LEFT JOIN (SELECT id AS operation_id,retry_count,requeue_count,next_run_at,error_code FROM deletion_operation) d
                ON d.operation_id=m.id AND m.kind IN ('DATA_DELETION','ACCOUNT_WITHDRAWAL')
            WHERE ((:operator AND kind IN ('CAMPAIGN','BULK_TAG'))
                OR (:privacy AND (kind IN ('APP_EXPORT','DATA_DELETION','ACCOUNT_WITHDRAWAL')
                    OR (kind='PII_RAW' AND actor_id=:owner)))
                OR (:analyst AND kind IN ('DASHBOARD_AGGREGATE','CONTENT_PERFORMANCE') AND actor_id=:owner))
            AND (NOT :deletions OR kind IN ('DATA_DELETION','ACCOUNT_WITHDRAWAL'))
            """;
    private static final String FILTERED = """
            AND (:kind IS NULL OR kind=:kind)
            AND (:status IS NULL OR status=:status)
            AND (CAST(:actor AS uuid) IS NULL OR actor_id=:actor)
            AND coalesce(requested_at,completed_at)>=:start AND coalesce(requested_at,completed_at)<:end
            """;
    private static final String FIELDS = """
            SELECT id,kind,status,actor_id,scope,requested_at,started_at,completed_at,expires_at,due_at,
                processed,total,coalesce(a.retry_count,d.retry_count,0) AS retry_count,
                coalesce(d.requeue_count,0) AS requeue_count,
                (coalesce(m.status='FAILED' AND a.retryable,false)
                OR CASE WHEN m.status='FAILED' AND d.retry_count=3 AND (
                    (m.kind='DATA_DELETION' AND d.error_code='SERVICE_DATA_PURGE_FAILED') OR
                    (m.kind='ACCOUNT_WITHDRAWAL' AND d.error_code='WITHDRAWAL_PURGE_FAILED'))
                THEN EXISTS(SELECT 1 FROM app_user u WHERE %s) ELSE false END) AS retryable,
                CASE m.status WHEN 'QUEUED' THEN a.next_run_at WHEN 'SCHEDULED' THEN d.next_run_at END AS next_run_at,
                coalesce(d.error_code,m.error_code) AS error_code,
                coalesce(status<>'CANCELED' AND
                    CASE WHEN status='COMPLETED' THEN completed_at ELSE now() END>due_at,false) AS overdue,
                coalesce(kind IN ('DATA_DELETION','ACCOUNT_WITHDRAWAL') AND status NOT IN ('COMPLETED','CANCELED')
                    AND due_at>=now() AND due_at<=now()+interval '1 hour',false) AS deadline_warning,
                CASE
                    WHEN kind='ACCOUNT_WITHDRAWAL' AND status='SCHEDULED' AND now()<due_at-interval '24 hours'
                        THEN 'GRACE_PERIOD'
                    WHEN kind='ACCOUNT_WITHDRAWAL' AND status='SCHEDULED' AND d.next_run_at IS NOT NULL
                        THEN 'PURGE_RETRY_WAIT'
                    WHEN kind='ACCOUNT_WITHDRAWAL' AND status='FAILED' THEN 'PURGE_REVIEW_REQUIRED'
                    WHEN kind='ACCOUNT_WITHDRAWAL' AND status='SCHEDULED' THEN 'AWAITING_PURGE'
                    WHEN kind='DATA_DELETION' AND status='SCHEDULED' AND d.next_run_at IS NOT NULL
                        THEN 'PURGE_RETRY_WAIT'
                    WHEN kind='DATA_DELETION' AND status='FAILED' THEN 'PURGE_REVIEW_REQUIRED'
                    WHEN kind='DATA_DELETION' AND status='SCHEDULED' THEN 'AWAITING_PURGE'
                    WHEN kind IN ('ACCOUNT_WITHDRAWAL','DATA_DELETION') THEN status
                END AS stage
            """.formatted(DELETION_ACCOUNT.formatted("d.operation_id", "m.kind"));

    private final JdbcClient db;
    private final AdminPii pii;
    private final AdminMutationJournal journal;

    AdminJobs(JdbcClient db, AdminPii pii, AdminMutationJournal journal) {
        this.db = db;
        this.pii = pii;
        this.journal = journal;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Page list(AdminAuth.Identity actor, Filter input, boolean deletions) {
        authorize(actor, deletions);
        var end = input.to() == null ? LocalDate.now(AdminUsers.KST) : input.to();
        var start = input.from() == null ? end.minusDays(29) : input.from();
        if (start.isAfter(end) || end.isAfter(start.plusDays(365))
                || input.page() < 1 || input.size() < 1 || input.size() > 100) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var total = filters(query("SELECT count(*) " + VISIBLE + FILTERED, actor, deletions), input, start, end)
                .query(Long.class).single();
        var items = filters(query(FIELDS + VISIBLE + FILTERED + """
                ORDER BY coalesce(requested_at,completed_at) DESC,id DESC
                LIMIT :size OFFSET :offset
                """, actor, deletions), input, start, end)
                .param("size", input.size()).param("offset", ((long) input.page() - 1) * input.size())
                .query(Job.class).list();
        return new Page(items, total, input.page(), input.size());
    }

    Job detail(AdminAuth.Identity actor, UUID id, boolean deletions) {
        authorize(actor, deletions);
        return query(FIELDS + VISIBLE + " AND id=:id", actor, deletions)
                .param("id", id).query(Job.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    Job requeueDeletion(AdminAuth.Identity actor, String session, UUID id, Requeue input, String key) {
        authorize(actor, true);
        pii.authorize(actor.admin().id(), AdminAuth.hash(session), false);
        var reason = input.reason() == null ? "" : input.reason().strip();
        int length = reason.codePointCount(0, reason.length());
        if (length == 0 || length > 500 || input.expectedRequeueCount() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return journal.once(actor, "/deletion-requests/" + id + "/retry", key,
                new Requeue(input.expectedRequeueCount(), reason), Job.class, () -> {
                    var current = detail(actor, id, true);
                    if (!current.retryable() || current.requeueCount() != input.expectedRequeueCount()) throw conflict();
                    db.sql("SELECT u.id FROM app_user u WHERE " + DELETION_ACCOUNT.formatted(":id", ":kind") + " FOR UPDATE OF u")
                            .param("id", id).param("kind", current.kind().name()).query(UUID.class)
                            .optional().orElseThrow(AdminJobs::conflict);
                    int updated = db.sql("""
                            UPDATE deletion_operation SET status='SCHEDULED',retry_count=0,requeue_count=requeue_count+1,
                                completed_at=NULL,next_run_at=NULL,error_code=NULL
                            WHERE id=:id AND kind=:kind AND status='FAILED' AND retry_count=3
                                AND requeue_count=:expected AND error_code=:error
                            """).param("id", id).param("kind", current.kind().name())
                            .param("expected", input.expectedRequeueCount()).param("error", current.errorCode()).update();
                    if (updated != 1) throw conflict();
                    return detail(actor, id, true);
                }, result -> journal.append(actor, "DELETION_JOB_REQUEUED", "JOB", id.toString(),
                        Map.of("kind", result.kind().name(), "reasonLength", length, "requeueCount", result.requeueCount())));
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT);
    }

    private JdbcClient.StatementSpec query(String sql, AdminAuth.Identity actor, boolean deletions) {
        return db.sql(sql).param("operator", actor.permissions().contains("OPERATOR"))
                .param("privacy", actor.permissions().contains("PRIVACY")).param("deletions", deletions)
                .param("analyst", actor.permissions().contains("ANALYST")).param("owner", actor.admin().id());
    }

    private static JdbcClient.StatementSpec filters(JdbcClient.StatementSpec query, Filter input,
                                                     LocalDate start, LocalDate end) {
        return query.param("kind", input.kind() == null ? null : input.kind().name(), Types.VARCHAR)
                .param("status", input.status() == null ? null : input.status().name(), Types.VARCHAR)
                .param("actor", input.actorId(), Types.OTHER)
                .param("start", start.atStartOfDay(AdminUsers.KST).toOffsetDateTime())
                .param("end", end.plusDays(1).atStartOfDay(AdminUsers.KST).toOffsetDateTime());
    }

    private static void authorize(AdminAuth.Identity actor, boolean deletions) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!actor.permissions().contains("PRIVACY") && (deletions
                || (!actor.permissions().contains("OPERATOR") && !actor.permissions().contains("ANALYST")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
