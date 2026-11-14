package kr.finsight.account;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.finsight.comparison.ComparisonInvalidation;
import kr.finsight.notification.DiscordOutbox;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("local")
class WithdrawalService {
    record Purge(UUID requestId, int retryCount, int requeueCount) {}
    private record Pending(UUID userId, String email) {}
    private final JdbcClient db;
    private final ComparisonInvalidation comparisons;
    private final AppSessions sessions;
    private final DiscordOutbox discord;

    WithdrawalService(JdbcClient db, AppSessions sessions, DiscordOutbox discord, ComparisonInvalidation comparisons) {
        this.db = db;
        this.comparisons = comparisons;
        this.sessions = sessions;
        this.discord = discord;
    }

    @Transactional
    AppSessions.Authentication reauthenticate(UUID user, UUID session, String password, String ip) {
        var email = db.sql("SELECT email FROM app_user WHERE id = :user AND status = 'ACTIVE'")
                .param("user", user).query(String.class).optional();
        if (email.isEmpty()) return new AppSessions.Authentication(null, false);
        var checked = sessions.authenticate(email.get(), password, ip, "ACTIVE");
        if (!user.equals(checked.userId())) return checked;
        return markReauthenticated(user, session) ? checked : new AppSessions.Authentication(null, false);
    }

    boolean markReauthenticated(UUID user, UUID session) {
        return db.sql("""
                UPDATE app_session SET reauthenticated_at = now() WHERE id = :session AND user_id = :user
                    AND expires_at > now()
                """).param("session", session).param("user", user).update() == 1;
    }

    @Transactional
    OffsetDateTime request(UUID user, UUID session) {
        var email = db.sql("SELECT email FROM app_user WHERE id = :user AND status = 'ACTIVE' FOR UPDATE")
                .param("user", user).query(String.class).optional();
        if (email.isEmpty()) return null;
        var verified = db.sql("""
                SELECT EXISTS (SELECT 1 FROM app_session WHERE user_id = :user AND id = :session
                    AND reauthenticated_at > now() - interval '5 minutes' AND expires_at > now())
                """).param("user", user).param("session", session).query(Boolean.class).single();
        if (!verified) return null;
        var requestId = UUID.randomUUID();
        var requested = OffsetDateTime.now(ZoneOffset.UTC);
        var due = requested.plusHours(336);
        db.sql("""
                UPDATE app_user SET status = 'WITHDRAWAL_PENDING', withdrawal_due_at = :due, withdrawal_request_id = :request,
                    content_event_epoch=content_event_epoch+1
                WHERE id = :user
                """).param("due", due).param("request", requestId).param("user", user).update();
        db.sql("""
                INSERT INTO deletion_operation(id,kind,status,requested_at,due_at)
                VALUES (:id,'ACCOUNT_WITHDRAWAL','SCHEDULED',:requested,:deadline)
                """).param("id", requestId).param("requested", requested).param("deadline", due.plusHours(24)).update();
        comparisons.invalidateForUser(user);
        db.sql("DELETE FROM app_data_export WHERE user_id = :user").param("user", user).update();
        db.sql("DELETE FROM app_session WHERE user_id = :user").param("user", user).update();
        db.sql("DELETE FROM app_password_reset WHERE user_id = :user").param("user", user).update();
        db.sql("""
                INSERT INTO app_event(event_id,event_name,user_id,occurred_at,received_at)
                VALUES (:operation,'USER_WITHDRAWAL_REQUESTED',:user,:requested,:requested)
                """).param("operation", requestId).param("user", user).param("requested", requested).update();
        discord.withdrawalRequested(requestId, email.get());
        return due;
    }

    @Transactional
    AppSessions.Authentication cancel(String email, String password, String ip) {
        var checked = sessions.authenticate(email, password, ip, "WITHDRAWAL_PENDING");
        if (checked.userId() == null) return checked;
        return cancelVerifiedUser(checked.userId()) ? checked : new AppSessions.Authentication(null, false);
    }

    boolean cancelVerifiedUser(UUID user) {
        var canceled = db.sql("""
                WITH pending AS (
                    SELECT id,withdrawal_request_id FROM app_user WHERE id=:user AND status='WITHDRAWAL_PENDING'
                    AND withdrawal_due_at>clock_timestamp() FOR UPDATE
                )
                UPDATE app_user u SET status='ACTIVE',withdrawal_due_at=NULL,withdrawal_request_id=NULL
                FROM pending WHERE u.id=pending.id AND u.withdrawal_due_at>clock_timestamp()
                RETURNING pending.withdrawal_request_id
                """).param("user", user).query(UUID.class).optional();
        if (canceled.isEmpty()) return false;
        db.sql("UPDATE deletion_operation SET status='CANCELED',completed_at=clock_timestamp() WHERE id=:id")
                .param("id", canceled.get()).update();
        return true;
    }

    List<Purge> duePurges() {
        return db.sql("""
                SELECT o.id AS request_id,o.retry_count,o.requeue_count FROM app_user u
                JOIN deletion_operation o ON o.id=u.withdrawal_request_id AND o.kind='ACCOUNT_WITHDRAWAL'
                WHERE u.status='WITHDRAWAL_PENDING' AND u.withdrawal_due_at<=clock_timestamp()
                    AND o.status='SCHEDULED' AND (o.next_run_at IS NULL OR o.next_run_at<=clock_timestamp())
                ORDER BY u.withdrawal_due_at,o.id LIMIT 100
                """).query(Purge.class).list();
    }

    @Transactional
    boolean finalizePurge(Purge purge) {
        return db.sql("""
                SELECT u.id AS user_id,u.email FROM app_user u JOIN deletion_operation o ON o.id=u.withdrawal_request_id
                WHERE o.id=:id AND o.kind='ACCOUNT_WITHDRAWAL' AND o.retry_count=:retry AND o.requeue_count=:requeue
                    AND u.status='WITHDRAWAL_PENDING' AND u.withdrawal_due_at<=clock_timestamp()
                    AND o.status='SCHEDULED' AND (o.next_run_at IS NULL OR o.next_run_at<=clock_timestamp())
                FOR UPDATE OF u,o SKIP LOCKED
                """).param("id", purge.requestId()).param("retry", purge.retryCount()).param("requeue", purge.requeueCount())
                .query(Pending.class).optional().map(pending -> {
            db.sql("UPDATE deletion_operation SET started_at=coalesce(started_at,clock_timestamp()) WHERE id=:id")
                    .param("id", purge.requestId()).update();
            db.sql("DELETE FROM signup_email_challenge WHERE email = :email").param("email", pending.email()).update();
            db.sql("DELETE FROM signup_email_delivery WHERE email = :email").param("email", pending.email()).update();
            db.sql("DELETE FROM app_login_failure WHERE email = :email").param("email", pending.email()).update();
            db.sql("DELETE FROM app_user WHERE id = :id").param("id", pending.userId()).update();
            long total = db.sql("""
                    UPDATE withdrawal_total SET completed_count = completed_count + 1 RETURNING completed_count
                    """).query(Long.class).single();
            discord.withdrawalCompleted(purge.requestId(), pending.email(), total);
            db.sql("""
                    UPDATE deletion_operation SET status='COMPLETED',completed_at=clock_timestamp(),
                        next_run_at=NULL,error_code=NULL WHERE id=:id
                    """).param("id", purge.requestId()).update();
            return true;
        }).orElse(false);
    }

    @Transactional
    void recordPurgeFailure(Purge purge) {
        // A retained pending account and an unchanged operation prove the atomic purge did not commit.
        db.sql("""
                WITH pending AS (
                    SELECT withdrawal_request_id FROM app_user
                    WHERE withdrawal_request_id=:id AND status='WITHDRAWAL_PENDING'
                        AND withdrawal_due_at<=clock_timestamp() FOR UPDATE SKIP LOCKED
                )
                UPDATE deletion_operation o SET
                    started_at=coalesce(started_at,clock_timestamp()),
                    retry_count=least(retry_count+1,3),
                    next_run_at=clock_timestamp()+CASE retry_count
                        WHEN 0 THEN interval '1 minute' WHEN 1 THEN interval '5 minutes' WHEN 2 THEN interval '15 minutes'
                    END,
                    status=CASE WHEN retry_count=3 THEN 'FAILED' ELSE status END,
                    completed_at=CASE WHEN retry_count=3 THEN clock_timestamp() END,
                    error_code='WITHDRAWAL_PURGE_FAILED'
                FROM pending WHERE o.id=pending.withdrawal_request_id AND o.kind='ACCOUNT_WITHDRAWAL'
                    AND o.status='SCHEDULED' AND o.retry_count=:retry AND o.requeue_count=:requeue
                    AND (o.next_run_at IS NULL OR o.next_run_at<=clock_timestamp())
                """).param("id", purge.requestId()).param("retry", purge.retryCount()).param("requeue", purge.requeueCount()).update();
    }
}
