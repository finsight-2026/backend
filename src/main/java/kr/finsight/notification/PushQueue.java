package kr.finsight.notification;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("local")
public class PushQueue {
    private final JdbcClient db;

    PushQueue(JdbcClient db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID notification, OffsetDateTime now, OffsetDateTime deadline) {
        db.sql("""
                INSERT INTO app_push_delivery (notification_id,session_id,provider,next_at,expires_at)
                SELECT n.id,r.session_id,r.provider,:now,least(:deadline,
                    CASE n.kind
                        WHEN 'EXPORT' THEN (SELECT expires_at-interval '23 hours'
                            FROM app_data_export WHERE id::text=n.event_key AND user_id=n.user_id)
                        WHEN 'NOTICE' THEN (SELECT ends_at FROM service_notice_revision WHERE id::text=n.event_key)
                    END)
                FROM app_notification n JOIN app_notification_local_slot slot ON slot.notification_id=n.id
                JOIN app_session s ON s.user_id=n.user_id JOIN app_push_registration r ON r.session_id=s.id
                WHERE n.id=:id AND s.expires_at>:now AND :deadline>:now
                ON CONFLICT DO NOTHING
                """).param("id", notification).param("now", now).param("deadline", deadline).update();
    }
}
