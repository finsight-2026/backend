package kr.finsight.admin;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.notification.LocalNotificationSchedule;
import kr.finsight.notification.PushQueue;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class AdminCampaignDispatch {
    enum DeliveryStatus { WAITING, LOCAL_INBOX, EXCLUDED, CANCELED, FAILED }
    record Counts(long waiting, long localInbox, long excluded, long canceled, long failed, long providerAccepted) {}
    record Delivery(UUID id, String recipient, DeliveryStatus status, String reason, OffsetDateTime updatedAt,
                    Map<String, Long> devices) {}
    record Deliveries(Counts counts, Map<String, Long> reasons, List<Delivery> items, String errorCode,
                      Map<String, Long> deviceCounts, long retryableDevices) {}
    record RetryReceipt(int requeued) {}
    private record Candidate(String status, boolean consent, boolean capped) {}
    private record Policy(boolean crmPaused, OffsetDateTime crmResumedAt) {}

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AdminCampaignDispatch.class);
    private final JdbcClient db;
    private final AdminCampaigns campaigns;
    private final LocalNotificationSchedule notifications;
    private final TransactionTemplate transactions;
    private final PushQueue pushQueue;
    private final AdminMutationJournal journal;
    private final JsonMapper json;

    AdminCampaignDispatch(JdbcClient db, AdminCampaigns campaigns, LocalNotificationSchedule notifications,
                          TransactionTemplate transactions, PushQueue pushQueue, AdminMutationJournal journal, JsonMapper json) {
        this.db = db;
        this.campaigns = campaigns;
        this.notifications = notifications;
        this.transactions = transactions;
        this.pushQueue = pushQueue;
        this.journal = journal;
        this.json = json;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    void dispatch() {
        db.sql("""
                SELECT c.id FROM campaign c JOIN campaign_job j ON j.campaign_id=c.id
                WHERE c.status='CANCEL_REQUESTED' OR
                    (c.status IN ('QUEUED','SCHEDULED','SENDING') AND c.scheduled_at<=clock_timestamp())
                ORDER BY (c.status='CANCEL_REQUESTED') DESC,c.scheduled_at,c.id LIMIT 20
                """).query(UUID.class).list().forEach(id -> {
            try {
                transactions.executeWithoutResult(status -> run(id, OffsetDateTime.now()));
            } catch (RuntimeException failure) {
                LOG.error("로컬 캠페인 작업 실패 {}", id, failure);
                transactions.executeWithoutResult(status -> {
                    var item = claim(id);
                    if (item != null && !item.status().equals("CANCEL_REQUESTED")) {
                        stop(id, "FAILED", DeliveryStatus.FAILED, "LOCAL_PROCESSING_ERROR");
                    }
                });
            }
        });
    }

    void run(UUID id, OffsetDateTime timestamp) {
        var item = claim(id);
        if (item == null) return;
        if (item.status().equals("CANCEL_REQUESTED")) {
            stop(id, "CANCELED", DeliveryStatus.CANCELED, "CANCEL_REQUESTED");
            return;
        }
        if (item.scheduledAt().isAfter(timestamp)) return;
        var policy = db.sql("SELECT crm_paused,crm_resumed_at FROM operating_settings WHERE singleton FOR SHARE")
                .query(Policy.class).single();
        if (policy.crmPaused()) return;
        if (policy.crmResumedAt() != null && item.scheduledAt().isBefore(policy.crmResumedAt())) {
            stop(id, "FAILED", DeliveryStatus.EXCLUDED, "PAUSED_WINDOW_SKIPPED");
            return;
        }
        var kst = timestamp.atZoneSameInstant(AdminUsers.KST);
        var close = item.scheduledAt().atZoneSameInstant(AdminUsers.KST).toLocalDate()
                .atTime(20, 0).atZone(AdminUsers.KST).toOffsetDateTime();
        if (kst.toLocalTime().isBefore(LocalTime.of(9, 0)) || !timestamp.isBefore(close)
                || !timestamp.isBefore(item.scheduledAt().plusHours(2))) {
            stop(id, "FAILED", DeliveryStatus.EXCLUDED, "WINDOW_EXPIRED");
            return;
        }
        if (!operator(id)) {
            stop(id, "FAILED", DeliveryStatus.EXCLUDED, "OPERATOR_INACTIVE");
            return;
        }
        if (!published(item.noticeRevisionId(), timestamp)) {
            stop(id, "FAILED", DeliveryStatus.EXCLUDED, "NOTICE_UNAVAILABLE");
            return;
        }

        var candidates = campaigns.candidates(item, kst.toLocalDate());
        if (!item.status().equals("SENDING")) {
            db.sql("""
                    INSERT INTO campaign_local_delivery(id,campaign_id,notice_revision_id,user_id,status)
                    SELECT gen_random_uuid(),:campaign,:notice,eligible.id,'WAITING' FROM (
                    """ + candidates.sql() + ") eligible ON CONFLICT (campaign_id,user_id) DO NOTHING")
                    .params(candidates.values()).param("campaign", id).param("notice", item.noticeRevisionId()).update();
            db.sql("UPDATE campaign_job SET started_at=:now WHERE campaign_id=:id")
                    .param("now", timestamp).param("id", id).update();
            state(id, "SENDING");
        }
        var batch = db.sql("""
                SELECT user_id FROM campaign_local_delivery WHERE campaign_id=:id AND status='WAITING'
                ORDER BY user_id LIMIT 25
                """).param("id", id).query(UUID.class).list();
        for (var user : batch) deliver(item, candidates, user, timestamp, close);
        if (!db.sql("SELECT EXISTS(SELECT 1 FROM campaign_local_delivery WHERE campaign_id=:id AND status='WAITING')")
                .param("id", id).query(Boolean.class).single()) {
            stop(id, "COMPLETED", DeliveryStatus.EXCLUDED, null);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Deliveries deliveries(UUID id, DeliveryStatus status) {
        campaigns.find(id);
        var counts = db.sql("""
                SELECT count(*) FILTER(WHERE status='WAITING'),count(*) FILTER(WHERE status='LOCAL_INBOX'),
                    count(*) FILTER(WHERE status='EXCLUDED'),count(*) FILTER(WHERE status='CANCELED'),
                    count(*) FILTER(WHERE status='FAILED'),
                    count(*) FILTER(WHERE EXISTS(SELECT 1 FROM app_push_delivery p
                        WHERE p.notification_id=d.notification_id AND p.state='ACCEPTED'))
                FROM campaign_local_delivery d WHERE campaign_id=:id
                """).param("id", id).query((row, n) -> new Counts(row.getLong(1), row.getLong(2),
                        row.getLong(3), row.getLong(4), row.getLong(5), row.getLong(6))).single();
        var rows = db.sql("""
                SELECT d.id,u.email,d.status,d.reason,d.updated_at,CAST(coalesce(p.states,'{}') AS text)
                FROM campaign_local_delivery d JOIN app_user u ON u.id=d.user_id
                LEFT JOIN LATERAL (
                    SELECT jsonb_object_agg(state,total) AS states FROM (
                        SELECT state,count(*) AS total FROM app_push_delivery
                        WHERE notification_id=d.notification_id GROUP BY state
                    ) grouped
                ) p ON true
                WHERE d.campaign_id=:id AND (:status IS NULL OR d.status=:status)
                ORDER BY d.updated_at DESC,d.id DESC LIMIT 100
                """).param("id", id).param("status", status == null ? null : status.name(), java.sql.Types.VARCHAR)
                .query((row, n) -> new Delivery(row.getObject(1, UUID.class), AdminUsers.mask(row.getString(2)),
                        DeliveryStatus.valueOf(row.getString(3)), row.getString(4), row.getObject(5, OffsetDateTime.class),
                        deviceStates(row.getString(6)))).list();
        var error = db.sql("SELECT error_code FROM campaign_job WHERE campaign_id=:id AND error_code IS NOT NULL")
                .param("id", id).query(String.class).optional().orElse(null);
        var reasons = new java.util.LinkedHashMap<String, Long>();
        db.sql("""
                SELECT reason,count(*) FROM campaign_local_delivery
                WHERE campaign_id=:id AND reason IS NOT NULL GROUP BY reason ORDER BY reason
                """).param("id", id).query((row, n) -> Map.entry(row.getString(1), row.getLong(2))).list()
                .forEach(entry -> reasons.put(entry.getKey(), entry.getValue()));
        var deviceCounts = new java.util.LinkedHashMap<String, Long>();
        db.sql("""
                SELECT p.state,count(*) FROM campaign_local_delivery d JOIN app_push_delivery p
                    ON p.notification_id=d.notification_id WHERE d.campaign_id=:id GROUP BY p.state ORDER BY p.state
                """).param("id", id).query((row, n) -> Map.entry(row.getString(1), row.getLong(2))).list()
                .forEach(entry -> deviceCounts.put(entry.getKey(), entry.getValue()));
        var retryable = db.sql("SELECT count(*) " + RETRY_CANDIDATES)
                .param("id", id).param("now", OffsetDateTime.now()).query(Long.class).single();
        return new Deliveries(counts, reasons, rows, error, deviceCounts, retryable);
    }

    private Map<String, Long> deviceStates(String value) {
        var states = new java.util.LinkedHashMap<String, Long>();
        json.readTree(value).properties().forEach(entry -> states.put(entry.getKey(), entry.getValue().asLong()));
        return states;
    }

    private static final String RETRY_CANDIDATES = """
            FROM campaign_local_delivery d JOIN campaign c ON c.id=d.campaign_id
            JOIN app_push_delivery p ON p.notification_id=d.notification_id
            JOIN app_session s ON s.id=p.session_id AND s.user_id=d.user_id AND s.expires_at>:now
            JOIN app_push_registration reg ON reg.session_id=s.id AND reg.provider=p.provider
            JOIN app_user u ON u.id=d.user_id AND u.status='ACTIVE'
            JOIN app_notification_preference pref ON pref.user_id=u.id AND pref.enabled
            JOIN service_notice_revision r ON r.id=d.notice_revision_id
            JOIN service_notice n ON n.published_revision_id=r.id AND n.kind='NOTICE'
            JOIN admin_account a ON a.id=c.actor_id AND a.status='ACTIVE'
            JOIN admin_role role ON role.admin_id=a.id AND role.role='OPERATOR'
            CROSS JOIN operating_settings policy
            WHERE c.id=:id AND c.status IN ('SENDING','COMPLETED') AND c.scheduled_at<=:now AND p.state='FAILED'
                AND p.manual_retry_at IS NULL AND greatest(p.next_at,:now)<least(p.expires_at,r.ends_at)
                AND r.status='PUBLISHED' AND r.starts_at<=:now
                AND NOT policy.crm_paused AND (policy.crm_resumed_at IS NULL OR policy.crm_resumed_at<=c.scheduled_at)
            """;

    RetryReceipt retryDevices(AdminAuth.Identity actor, UUID id, String key, String reason, OffsetDateTime now) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return journal.once(actor, "/campaigns/" + id + "/deliveries/retry", key, reason.strip(), RetryReceipt.class, () -> {
            db.sql("SELECT id FROM campaign WHERE id=:id FOR UPDATE").param("id", id).query(UUID.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            int count = db.sql("""
                    WITH candidates AS (SELECT p.notification_id,p.session_id
                    """ + RETRY_CANDIDATES + """
                    )
                    UPDATE app_push_delivery q SET state='WAITING',next_at=greatest(q.next_at,:now),manual_retry_at=:now
                    FROM candidates
                    WHERE q.notification_id=candidates.notification_id AND q.session_id=candidates.session_id
                        AND q.state='FAILED' AND q.manual_retry_at IS NULL
                    """).param("id", id).param("now", now).update();
            if (count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT);
            return new RetryReceipt(count);
        }, receipt -> journal.append(actor, "CRM_DELIVERY_RETRY", "CAMPAIGN", id.toString(),
                Map.of("count", receipt.requeued(), "reasonLength", reason.strip().length())));
    }

    private void deliver(AdminCampaigns.Campaign item, AdminSegments.CandidateQuery candidates, UUID user, OffsetDateTime timestamp, OffsetDateTime close) {
        var locked = db.sql("SELECT id FROM app_user WHERE id=:id FOR NO KEY UPDATE SKIP LOCKED")
                .param("id", user).query(UUID.class).optional();
        if (locked.isEmpty()) return;
        var condition = db.sql("SELECT status,consent,capped FROM (" + candidates.sql() + ") candidate WHERE id=:user")
                .params(candidates.values()).param("user", user)
                .query((row, n) -> new Candidate(row.getString(1), row.getBoolean(2), row.getBoolean(3))).optional();
        String reason = exclusion(item.noticeRevisionId(), user, condition.orElse(null), timestamp);
        if (reason != null) {
            result(item.id(), user, DeliveryStatus.EXCLUDED, reason, null);
            return;
        }
        var notification = db.sql("""
                INSERT INTO app_notification(id,user_id,kind,event_key,title,body,created_at,template_id,template_version)
                VALUES (:id,:user,'NOTICE',:key,:title,:body,:time,'NTF-06','1.0')
                ON CONFLICT(user_id,kind,event_key) DO NOTHING RETURNING id
                """).param("id", UUID.randomUUID()).param("user", user).param("key", item.noticeRevisionId().toString())
                .param("title", item.title()).param("body", item.body()).param("time", timestamp).query(UUID.class).optional();
        if (notification.isEmpty()) {
            result(item.id(), user, DeliveryStatus.EXCLUDED, "DUPLICATE_NOTICE", null);
            return;
        }
        db.sql("""
                INSERT INTO app_notification_local_slot(notification_id,user_id,delivery_day,category)
                VALUES (:id,:user,:day,'REMINDER')
                """).param("id", notification.get()).param("user", user)
                .param("day", timestamp.atZoneSameInstant(AdminUsers.KST).toLocalDate()).update();
        var deadline = item.scheduledAt().plusHours(2);
        pushQueue.enqueue(notification.get(), timestamp, close.isBefore(deadline) ? close : deadline);
        result(item.id(), user, DeliveryStatus.LOCAL_INBOX, null, notification.get());
    }

    private String exclusion(UUID notice, UUID user, Candidate item, OffsetDateTime timestamp) {
        if (item == null) return "NO_LONGER_MATCHES";
        if (!item.status().equals("ACTIVE")) return "WITHDRAWAL_PENDING";
        if (!item.consent()) return "OPTED_OUT";
        if (db.sql("""
                SELECT EXISTS(SELECT 1 FROM campaign_local_delivery
                    WHERE user_id=:user AND notice_revision_id=:notice AND status='LOCAL_INBOX')
                """).param("user", user).param("notice", notice).query(Boolean.class).single()) return "DUPLICATE_NOTICE";
        if (item.capped()) return "DAILY_CAP";
        if (notifications.hasEveningReminder(user, timestamp)) return "REMINDER_PRIORITY";
        return null;
    }

    private void result(UUID campaign, UUID user, DeliveryStatus status, String reason, UUID notification) {
        db.sql("""
                UPDATE campaign_local_delivery SET status=:status,reason=:reason,notification_id=:notification,
                    updated_at=clock_timestamp() WHERE campaign_id=:campaign AND user_id=:user AND status='WAITING'
                """).param("status", status.name()).param("reason", reason, java.sql.Types.VARCHAR)
                .param("notification", notification, java.sql.Types.OTHER)
                .param("campaign", campaign).param("user", user).update();
    }

    private AdminCampaigns.Campaign claim(UUID id) {
        return db.sql("""
                SELECT c.id FROM campaign c JOIN campaign_job j ON j.campaign_id=c.id
                WHERE c.id=:id AND c.status IN ('QUEUED','SCHEDULED','SENDING','CANCEL_REQUESTED')
                FOR UPDATE OF c SKIP LOCKED
                """).param("id", id).query(UUID.class).optional().map(campaigns::find).orElse(null);
    }

    private boolean operator(UUID id) {
        return db.sql("""
                SELECT a.id FROM campaign c JOIN admin_account a ON a.id=c.actor_id
                JOIN admin_role r ON r.admin_id=a.id AND r.role='OPERATOR'
                WHERE c.id=:id AND a.status='ACTIVE' FOR SHARE OF a,r
                """).param("id", id).query(UUID.class).optional().isPresent();
    }

    private boolean published(UUID revision, OffsetDateTime timestamp) {
        return db.sql("""
                SELECT r.id FROM service_notice n JOIN service_notice_revision r ON r.id=n.published_revision_id
                WHERE n.kind='NOTICE' AND r.id=:id AND r.status='PUBLISHED' AND r.starts_at<=:time
                AND (r.ends_at IS NULL OR r.ends_at>:time) FOR SHARE OF n,r
                """).param("id", revision).param("time", timestamp).query(UUID.class).optional().isPresent();
    }

    private void stop(UUID id, String status, DeliveryStatus remaining, String reason) {
        db.sql("""
                UPDATE campaign_local_delivery SET status=:status,reason=:reason,updated_at=clock_timestamp()
                WHERE campaign_id=:id AND status='WAITING'
                """).param("id", id).param("status", remaining.name()).param("reason", reason, java.sql.Types.VARCHAR).update();
        db.sql("UPDATE campaign_job SET completed_at=clock_timestamp(),error_code=:code WHERE campaign_id=:id")
                .param("id", id).param("code", reason, java.sql.Types.VARCHAR).update();
        if (status.equals("FAILED") && db.sql("""
                SELECT EXISTS(SELECT 1 FROM campaign_local_delivery WHERE campaign_id=:id AND status='LOCAL_INBOX')
                """).param("id", id).query(Boolean.class).single()) {
            status = "PARTIAL_FAILED";
        }
        state(id, status);
    }

    private void state(UUID id, String status) {
        db.sql("UPDATE campaign SET status=:status,version=version+1,updated_at=clock_timestamp() WHERE id=:id")
                .param("id", id).param("status", status).update();
    }
}
