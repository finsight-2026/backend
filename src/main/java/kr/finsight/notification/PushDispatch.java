package kr.finsight.notification;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("local")
class PushDispatch {
    private record Claim(UUID notification, UUID session, String token, int attempt, boolean manual, PushProvider.Message message) {}
    private record Candidate(UUID notification, UUID session, UUID user, String token, String kind, String key,
                             String template, String version, String title, String body, int attempts, OffsetDateTime created,
                             OffsetDateTime expires, boolean manual) {}

    private final JdbcClient db;
    private final TransactionTemplate transactions;
    private final LocalNotificationSchedule schedule;
    private final List<PushProvider> providers;

    PushDispatch(JdbcClient db, TransactionTemplate transactions, LocalNotificationSchedule schedule,
                 List<PushProvider> providers) {
        this.db = db;
        this.transactions = transactions;
        this.schedule = schedule;
        this.providers = providers;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 30000)
    void dispatch() {
        var now = OffsetDateTime.now();
        expire(now);
        for (var provider : providers) {
            for (int i = 0; i < 50; i++) {
                if (!process(provider, OffsetDateTime.now())) break;
            }
        }
    }

    void expire(OffsetDateTime now) {
        db.sql("""
                UPDATE app_push_delivery SET state=CASE WHEN state='SENDING' THEN 'UNKNOWN' ELSE 'EXPIRED' END
                WHERE (state='WAITING' AND expires_at<=:now)
                    OR (state='SENDING' AND claimed_at<=:cutoff)
                """).param("now", now).param("cutoff", now.minusMinutes(5)).update();
    }

    boolean process(PushProvider provider, OffsetDateTime now) {
        var claim = transactions.execute(status -> claim(provider.provider(), now));
        if (claim == null) return false;
        if (claim.message() == null) return true;
        var outcome = PushProvider.Result.of(PushProvider.Outcome.UNKNOWN);
        try {
            var result = provider.send(claim.token(), claim.message());
            if (result != null) outcome = result;
        } catch (RuntimeException uncertain) {
            // A provider timeout or unexpected error may follow successful acceptance.
        }
        var result = outcome;
        transactions.executeWithoutResult(status -> finish(claim, provider.provider(), result, now));
        return true;
    }

    private Claim claim(PushRegistrations.Provider provider, OffsetDateTime now) {
        var selected = db.sql("""
                SELECT q.notification_id AS notification,q.session_id AS session,n.user_id AS user,
                    r.registration_token AS token,n.kind,n.event_key AS key,n.template_id AS template,
                    n.template_version AS version,n.title,n.body,q.attempts,n.created_at AS created,
                    least(q.expires_at, CASE n.kind
                        WHEN 'NOTICE' THEN (SELECT ends_at FROM service_notice_revision WHERE id::text=n.event_key)
                        WHEN 'EXPORT' THEN (SELECT expires_at FROM app_data_export WHERE id::text=n.event_key)
                    END) AS expires,q.manual_retry_at IS NOT NULL AS manual
                FROM app_push_delivery q JOIN app_notification n ON n.id=q.notification_id
                LEFT JOIN app_session s ON s.id=q.session_id
                LEFT JOIN app_user u ON u.id=n.user_id AND u.status='ACTIVE'
                LEFT JOIN app_push_registration r ON r.session_id=s.id AND r.provider=q.provider
                    AND s.expires_at>:now AND s.user_id=n.user_id AND u.id IS NOT NULL
                WHERE q.state='WAITING' AND q.provider=:provider AND q.next_at<=:now AND q.expires_at>:now
                ORDER BY q.next_at,q.notification_id,q.session_id LIMIT 1 FOR UPDATE OF q SKIP LOCKED
                """).param("now", now).param("provider", provider.name()).query(Candidate.class).optional();
        if (selected.isEmpty()) return null;
        var item = selected.get();
        if (item.token() == null || !item.expires().isAfter(now)
                || !schedule.eligibleForDelivery(item.notification(), item.user(), item.kind(), item.key(), item.created(), now)) {
            db.sql("""
                    UPDATE app_push_delivery SET state='CANCELED'
                    WHERE notification_id=:id AND session_id=:session
                    """).param("id", item.notification()).param("session", item.session()).update();
            return new Claim(item.notification(), item.session(), null, 0, false, null);
        }
        db.sql("""
                UPDATE app_push_delivery SET state='SENDING',claimed_at=:now,attempts=attempts+1
                WHERE notification_id=:id AND session_id=:session
                """).param("id", item.notification()).param("session", item.session()).param("now", now).update();
        var resource = item.kind().equals("GUIDE") ? item.key().split(":", 2)[0] : item.key();
        var destination = switch (item.kind()) {
            case "INCOMPLETE", "PERIODIC" -> "DIAGNOSIS";
            default -> item.kind();
        };
        var clock = now.atZoneSameInstant(LocalNotificationSchedule.KST).toLocalTime();
        boolean silent = item.kind().equals("EXPORT")
                && (clock.isBefore(LocalTime.of(8, 0)) || !clock.isBefore(LocalTime.of(21, 0)));
        return new Claim(item.notification(), item.session(), item.token(), item.attempts() + 1, item.manual(),
                new PushProvider.Message(item.notification(), item.template(), item.version(), destination,
                        resource, item.title(), item.body(), item.expires(), silent));
    }

    private void finish(Claim claim, PushRegistrations.Provider provider, PushProvider.Result result, OffsetDateTime now) {
        var outcome = result.outcome();
        boolean campaign = "NTF-06".equals(claim.message().templateId());
        long backoffSeconds = campaign
                ? switch (claim.attempt()) { case 1 -> 60; case 2 -> 300; case 3 -> 900; default -> 0; }
                : 30L << Math.min(5, claim.attempt() - 1);
        var backoffUntil = now.plusSeconds(backoffSeconds);
        var notBefore = result.notBefore();
        var retryAt = notBefore != null && notBefore.isAfter(backoffUntil) ? notBefore : backoffUntil;
        var next = outcome.name();
        if (outcome == PushProvider.Outcome.RETRY) {
            if (!retryAt.isBefore(claim.message().expiresAt())) next = "EXPIRED";
            else if (campaign && (claim.attempt() >= 4 || claim.manual())) next = "FAILED";
            else next = "WAITING";
        }
        var changed = db.sql("""
                UPDATE app_push_delivery SET state=:state,next_at=:retry
                WHERE notification_id=:id AND session_id=:session AND state='SENDING'
                """).param("id", claim.notification()).param("session", claim.session())
                .param("state", next).param("retry", retryAt).update();
        if (changed == 1 && outcome == PushProvider.Outcome.INVALID) {
            db.sql("DELETE FROM app_push_registration WHERE session_id=:session AND registration_token=:token AND provider=:provider")
                    .param("session", claim.session()).param("token", claim.token())
                    .param("provider", provider.name()).update();
        }
    }
}
