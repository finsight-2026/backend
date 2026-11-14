package kr.finsight.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.UUID;
import kr.finsight.comparison.ComparisonInvalidation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class DataDeletionService {
    record Receipt(UUID requestId, String status, OffsetDateTime completedAt, @JsonIgnore long contentEventEpoch) {}
    record Job(UUID operationId, UUID userId, int retryCount, int requeueCount) {}
    private static final String RECEIPTS = """
            SELECT r.request_id,r.completed_at,u.content_event_epoch,
                CASE WHEN r.completed_at IS NOT NULL THEN 'COMPLETED' WHEN o.status='FAILED' THEN 'FAILED' ELSE 'PENDING' END
            FROM app_data_deletion r JOIN app_user u ON u.id=r.user_id
            LEFT JOIN deletion_operation o ON o.id=r.operation_id
            WHERE r.user_id=:user AND u.status IN ('ACTIVE','DATA_DELETION_PENDING')
            """;

    private final JdbcClient db;
    private final ComparisonInvalidation comparisons;

    DataDeletionService(JdbcClient db, ComparisonInvalidation comparisons) {
        this.db = db;
        this.comparisons = comparisons;
    }

    @Transactional
    Receipt request(UUID user, UUID session, UUID requestId) {
        db.sql("SELECT id FROM app_user WHERE id=:user AND status IN ('ACTIVE','DATA_DELETION_PENDING') FOR UPDATE")
                .param("user", user).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        var validSession = db.sql("""
                SELECT reauthenticated_at > clock_timestamp() - interval '5 minutes' AS recent
                FROM app_session WHERE id = :session AND user_id = :user AND expires_at > clock_timestamp()
                FOR UPDATE
                """).param("session", session).param("user", user)
                .query((row, index) -> row.getBoolean("recent")).optional();
        if (validSession.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        var existing = receipt(user, requestId);
        if (existing.isPresent()) return existing.get();
        var pending = pending(user);
        if (pending.isPresent()) return pending.get();
        if (!validSession.get()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        var started = db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        var epoch = db.sql("""
                UPDATE app_user SET status='DATA_DELETION_PENDING',content_event_epoch=content_event_epoch+1
                WHERE id=:user RETURNING content_event_epoch
                """).param("user", user).query(Long.class).single();
        db.sql("""
                UPDATE discord_outbox SET state = 'FAILED', content = NULL
                WHERE event_type = 'SURVEY' AND state = 'PENDING' AND event_id IN (
                    SELECT d.id FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                    WHERE a.user_id = :user
                )
                """).param("user", user).update();
        comparisons.invalidateForUser(user);
        var operation = UUID.randomUUID();
        db.sql("""
                INSERT INTO deletion_operation(id,kind,status,requested_at,due_at)
                VALUES (:id,'DATA_DELETION','SCHEDULED',:start,:start+interval '24 hours')
                """).param("id", operation).param("start", started).update();
        db.sql("""
                INSERT INTO app_data_deletion(user_id,request_id,operation_id) VALUES (:user,:request,:operation)
                """).param("user", user).param("request", requestId).param("operation", operation).update();
        db.sql("""
                INSERT INTO app_event(event_id,event_name,user_id,occurred_at,received_at)
                VALUES (:operation,'USER_DELETION_REQUESTED',:user,:start,:start)
                """).param("operation", operation).param("user", user).param("start", started).update();
        return new Receipt(requestId, "PENDING", null, epoch);
    }

    java.util.List<Job> dueJobs() {
        return db.sql("""
                SELECT o.id AS operation_id,r.user_id,o.retry_count,o.requeue_count
                FROM deletion_operation o JOIN app_data_deletion r ON r.operation_id=o.id
                WHERE o.kind='DATA_DELETION' AND o.status='SCHEDULED' AND r.completed_at IS NULL
                    AND (o.next_run_at IS NULL OR o.next_run_at<=statement_timestamp())
                ORDER BY o.requested_at,o.id LIMIT 100
                """).query(Job.class).list();
    }

    @Transactional
    boolean purge(Job job) {
        var user = job.userId();
        if (!lockPending(job)) return false;
        db.sql("UPDATE deletion_operation SET started_at=coalesce(started_at,clock_timestamp()) WHERE id=:id")
                .param("id", job.operationId()).update();
        db.sql("DELETE FROM app_data_export WHERE user_id = :user").param("user", user).update();
        db.sql("DELETE FROM app_notification WHERE user_id = :user AND kind <> 'ECONOMY'")
                .param("user", user).update();
        db.sql("DELETE FROM app_foreground_day WHERE user_id = :user").param("user", user).update();
        db.sql("DELETE FROM screen_experiment_assignment WHERE user_id=:user").param("user", user).update();
        db.sql("""
                DELETE FROM app_event WHERE user_id=:user AND event_name NOT IN ('USER_SIGNED_UP','PROFILE_CONSENT_COMPLETED')
                AND event_id<>:operation
                """).param("user", user).param("operation", job.operationId()).update();
        db.sql("DELETE FROM insight_helpful WHERE user_id=:user").param("user", user).update();
        db.sql("DELETE FROM guide_activity WHERE user_id = :user").param("user", user).update();
        db.sql("DELETE FROM survey_attempt WHERE user_id = :user").param("user", user).update();
        db.sql("DELETE FROM diagnosis_journey WHERE user_id = :user").param("user", user).update();
        db.sql("DELETE FROM operator_note WHERE user_id = :user").param("user", user).update();
        db.sql("DELETE FROM campaign_target_preview_user WHERE user_id=:user").param("user", user).update();
        db.sql("DELETE FROM admin_masked_export_user WHERE user_id=:user").param("user", user).update();
        db.sql("DELETE FROM pii_grant WHERE user_id = :user").param("user", user).update();
        var completed = db.sql("""
                UPDATE app_data_deletion SET completed_at=clock_timestamp() WHERE operation_id=:id RETURNING completed_at
                """).param("id", job.operationId()).query(OffsetDateTime.class).single();
        db.sql("""
                UPDATE deletion_operation SET status='COMPLETED',completed_at=:completed,next_run_at=NULL,error_code=NULL
                WHERE id=:id
                """).param("id", job.operationId()).param("completed", completed).update();
        db.sql("UPDATE app_user SET status='ACTIVE' WHERE id=:user").param("user", user).update();
        return true;
    }

    @Transactional
    void recordFailure(Job job) {
        if (!lockPending(job)) return;
        db.sql("""
                UPDATE deletion_operation SET started_at=coalesce(started_at,clock_timestamp()),
                    retry_count=least(retry_count+1,3),
                    next_run_at=clock_timestamp()+CASE retry_count
                        WHEN 0 THEN interval '1 minute' WHEN 1 THEN interval '5 minutes' WHEN 2 THEN interval '15 minutes'
                    END,
                    status=CASE WHEN retry_count=3 THEN 'FAILED' ELSE status END,
                    completed_at=CASE WHEN retry_count=3 THEN clock_timestamp() END,
                    error_code='SERVICE_DATA_PURGE_FAILED'
                WHERE id=:id
                """).param("id", job.operationId()).update();
    }

    private boolean lockPending(Job job) {
        return db.sql("""
                SELECT u.id FROM app_user u JOIN app_data_deletion r ON r.user_id=u.id
                JOIN deletion_operation o ON o.id=r.operation_id
                WHERE u.id=:user AND u.status='DATA_DELETION_PENDING' AND r.completed_at IS NULL AND o.id=:id
                    AND o.status='SCHEDULED' AND o.retry_count=:retry AND o.requeue_count=:requeue
                    AND (o.next_run_at IS NULL OR o.next_run_at<=statement_timestamp())
                FOR UPDATE OF u,o SKIP LOCKED
                """).param("user", job.userId()).param("id", job.operationId()).param("retry", job.retryCount())
                .param("requeue", job.requeueCount())
                .query(UUID.class).optional().isPresent();
    }

    java.util.Optional<Receipt> receipt(UUID user, UUID requestId) {
        return db.sql(RECEIPTS + " AND r.request_id=:request").param("user", user).param("request", requestId)
                .query(this::readReceipt).optional();
    }

    java.util.Optional<Receipt> pending(UUID user) {
        return db.sql(RECEIPTS + " AND r.completed_at IS NULL").param("user", user).query(this::readReceipt).optional();
    }

    private Receipt readReceipt(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Receipt(row.getObject(1, UUID.class), row.getString(4), row.getObject(2, OffsetDateTime.class), row.getLong(3));
    }
}
