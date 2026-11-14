package kr.finsight.notification;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class PushRegistrations {
    enum Provider { FCM, FCM_FID, LOCAL }

    private final JdbcClient db;

    PushRegistrations(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    void put(UUID user, UUID session, Provider provider, String token) {
        lockActiveSession(user, session);
        var enabled = db.sql("SELECT enabled FROM app_notification_preference WHERE user_id=:user")
                .param("user", user).query(Boolean.class).optional().orElse(false);
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "전체 알림 설정을 확인해주세요.");
        }
        // A new session on the same installation takes over its provider registration.
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "push-registration:" + provider.name() + ":" + token)
                .query((row, index) -> true).single();
        db.sql("""
                DELETE FROM app_push_registration
                WHERE provider=:provider AND session_id<>:session
                AND sha256(registration_token::bytea)=sha256(CAST(:token AS bytea))
                AND registration_token=:token
                """).param("provider", provider.name()).param("token", token).param("session", session).update();
        db.sql("""
                INSERT INTO app_push_registration (session_id, provider, registration_token)
                VALUES (:session, :provider, :token)
                ON CONFLICT (session_id) DO UPDATE SET provider=excluded.provider,
                    registration_token=excluded.registration_token, updated_at=clock_timestamp()
                """).param("session", session).param("provider", provider.name()).param("token", token).update();
    }

    @Transactional
    void remove(UUID user, UUID session) {
        lockActiveSession(user, session);
        db.sql("DELETE FROM app_push_registration WHERE session_id=:session")
                .param("session", session).update();
    }

    @Scheduled(cron = "0 25 3 * * *", zone = "Asia/Seoul")
    void expire() {
        db.sql("""
                WITH expired AS (
                    SELECT s.id FROM app_session s JOIN app_push_registration r ON r.session_id=s.id
                    WHERE s.expires_at<=clock_timestamp() FOR UPDATE OF s SKIP LOCKED
                )
                DELETE FROM app_push_registration r USING expired WHERE r.session_id=expired.id
                """).update();
    }

    private void lockActiveSession(UUID user, UUID session) {
        var active = db.sql("SELECT id FROM app_user WHERE id=:user AND status='ACTIVE' FOR SHARE")
                .param("user", user).query(UUID.class).optional().isPresent();
        if (!active || db.sql("""
                SELECT id FROM app_session
                WHERE id=:session AND user_id=:user AND expires_at>clock_timestamp()
                FOR UPDATE
                """).param("user", user).param("session", session).query(UUID.class).optional().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
