package kr.finsight.notification;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("local")
public class LocalNotificationSchedule {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MORNING = LocalTime.of(8, 0);
    private static final LocalTime EVENING = LocalTime.of(20, 0);
    private final JdbcClient db;
    private final TransactionTemplate transaction;
    private final PushQueue pushQueue;

    LocalNotificationSchedule(JdbcClient db, TransactionTemplate transaction, PushQueue pushQueue) {
        this.db = db;
        this.transaction = transaction;
        this.pushQueue = pushQueue;
    }

    enum Template {
        EXPORT("NTF-05", "요청하신 파일이 준비됐어요", "앱에서 파일을 내려받을 수 있어요. 준비 완료 후 24시간 안에 확인해주세요."),
        INCOMPLETE("NTF-02", "처음부터 다시 시작해볼까요?", "지난 응답은 저장되지 않았어요. 여유가 생겼을 때 새로 진단해보세요."),
        PERIODIC("NTF-01", "요즘 금융 마음은 어떤가요?", "약 5분, 지금의 나를 살펴보세요. 지난 기록과 변화도 확인할 수 있어요."),
        GUIDE("NTF-03", "작은 행동 하나, 해볼까요?", "살펴본 가이드에 오늘 해볼 작은 행동이 있어요. 부담 없이 확인해보세요."),
        ECONOMY("NTF-04", "오늘의 경제 소식이 도착했어요", "생활과 가까운 경제 소식을 짧게 정리했어요. 오늘의 핵심을 확인해보세요.");

        final String id;
        final String title;
        final String body;

        Template(String id, String title, String body) {
            this.id = id;
            this.title = title;
            this.body = body;
        }
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Seoul")
    void expire() {
        db.sql("DELETE FROM app_notification WHERE created_at <= now() - interval '90 days'").update();
        db.sql("""
                DELETE FROM app_notification_local_slot
                WHERE delivery_day < (now() AT TIME ZONE 'Asia/Seoul')::date - 90
                """).update();
    }

    record Preference(boolean incomplete, boolean periodic, boolean guide, boolean economy) {}
    private record Policy(boolean paused, OffsetDateTime resumedAt) {
        boolean permits(OffsetDateTime scheduled) {
            return !paused && (resumedAt == null || !scheduled.isBefore(resumedAt));
        }
    }

    private Policy policy() {
        return db.sql("SELECT automatic_push_paused AS paused,automatic_push_resumed_at AS resumed_at FROM operating_settings WHERE singleton FOR SHARE")
                .query(Policy.class).single();
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    void run() {
        UUID cursor = null;
        while (true) {
            var users = db.sql("""
                    SELECT user_id FROM app_notification_preference
                    WHERE enabled AND user_id > coalesce(:cursor, '00000000-0000-0000-0000-000000000000'::uuid)
                    ORDER BY user_id LIMIT 100
                    """).param("cursor", cursor, java.sql.Types.OTHER).query(UUID.class).list();
            for (var user : users) process(user, OffsetDateTime.now());
            if (users.size() < 100) return;
            cursor = users.getLast();
        }
    }

    void process(UUID user, OffsetDateTime timestamp) {
        var now = timestamp.atZoneSameInstant(KST).toOffsetDateTime();
        var date = now.toLocalDate();
        var morning = date.atTime(MORNING).atZone(KST).toOffsetDateTime();
        var evening = date.atTime(EVENING).atZone(KST).toOffsetDateTime();
        transaction.executeWithoutResult(status -> {
            var active = db.sql("SELECT id FROM app_user WHERE id=:user AND status='ACTIVE' FOR UPDATE SKIP LOCKED")
                    .param("user", user).query(UUID.class).optional();
            if (active.isEmpty()) return;
            var policy = policy();
            if (policy.paused()) return;
            var preference = preference(user);
            if (preference.isEmpty()) return;
            var allowed = preference.get();
            exports(user, now, policy.resumedAt());
            if (allowed.economy() && policy.permits(morning) && inWindow(now, morning, 4)) economy(user, now, morning);
            if (policy.permits(evening) && inWindow(now, evening, 2)) {
                if (allowed.incomplete()) incomplete(user, evening).ifPresent(key -> add(user, Template.INCOMPLETE, key, now));
                if (allowed.periodic()) periodic(user, evening).ifPresent(key -> add(user, Template.PERIODIC, key, now));
                if (allowed.guide()) guides(user, evening).forEach(key -> add(user, Template.GUIDE,
                        key + ":" + java.time.YearMonth.from(now), now));
            }
        });
    }

    private java.util.Optional<Preference> preference(UUID user) {
        return db.sql("""
                SELECT incomplete,periodic,guide,economy FROM app_notification_preference
                WHERE user_id=:user AND enabled
                """).param("user", user).query((row, n) -> new Preference(row.getBoolean(1),
                row.getBoolean(2), row.getBoolean(3), row.getBoolean(4))).optional();
    }

    public boolean hasEveningReminder(UUID user, OffsetDateTime timestamp) {
        var evening = timestamp.atZoneSameInstant(KST).toLocalDate().atTime(EVENING).atZone(KST).toOffsetDateTime();
        if (!policy().permits(evening)) return false;
        return preference(user).map(allowed ->
                (allowed.incomplete() && incomplete(user, evening).isPresent())
                || (allowed.periodic() && periodic(user, evening).isPresent())
                || (allowed.guide() && !guides(user, evening).isEmpty())).orElse(false);
    }

    private static boolean inWindow(OffsetDateTime now, OffsetDateTime start, int hours) {
        return !now.isBefore(start) && now.isBefore(start.plusHours(hours));
    }

    private void exports(UUID user, OffsetDateTime now, OffsetDateTime resumed) {
        db.sql("""
                SELECT id::text FROM app_data_export WHERE user_id=:user AND status='COMPLETED' AND archive IS NOT NULL
                AND expires_at > :now AND expires_at - interval '24 hours' <= :now
                AND expires_at - interval '23 hours' > :now
                AND (CAST(:resumed AS timestamptz) IS NULL OR expires_at-interval '24 hours'>=:resumed)
                ORDER BY expires_at,id
                """).param("user", user).param("now", now)
                .param("resumed", resumed, java.sql.Types.TIMESTAMP_WITH_TIMEZONE).query(String.class).list()
                .forEach(key -> add(user, Template.EXPORT, key, now));
    }

    private void economy(UUID user, OffsetDateTime now, OffsetDateTime morning) {
        if (economyEligible(user, now, morning)) add(user, Template.ECONOMY, now.toLocalDate().toString(), now);
    }

    private boolean economyEligible(UUID user, OffsetDateTime now, OffsetDateTime morning) {
        return db.sql("""
                WITH latest AS (
                    SELECT d.representative_type FROM diagnosis d JOIN survey_attempt a ON a.id=d.attempt_id
                    WHERE a.user_id=:user ORDER BY d.completed_at DESC,d.id DESC LIMIT 1
                )
                SELECT EXISTS (
                    SELECT 1 FROM news_edition_item e JOIN news_article a ON a.id=e.article_id
                    JOIN news_revision r ON r.article_id=a.id AND r.revision=e.article_revision AND r.revision=a.published_revision
                    WHERE e.edition_date=:day AND e.assigned_at<=:morning AND e.type=ANY(r.types)
                    AND (NOT EXISTS (SELECT 1 FROM latest) OR e.type=(SELECT representative_type FROM latest))
                    AND r.status='PUBLISHED' AND r.starts_at>=:dayStart AND r.starts_at<=:morning
                    AND r.updated_at<=:morning
                    AND (r.ends_at IS NULL OR r.ends_at>:now)
                )
                """).param("user", user).param("day", now.toLocalDate()).param("dayStart", morning.minusHours(8))
                .param("morning", morning).param("now", now).query(Boolean.class).single();
    }

    private java.util.Optional<String> incomplete(UUID user, OffsetDateTime evening) {
        return db.sql("""
                WITH latest AS (
                    SELECT id,aborted_at FROM survey_attempt WHERE user_id=:user AND aborted_at IS NOT NULL
                    ORDER BY aborted_at DESC,id DESC LIMIT 1
                )
                SELECT id::text FROM latest WHERE aborted_at>:lower AND aborted_at<=:upper
                AND NOT EXISTS (
                    SELECT 1 FROM survey_attempt a LEFT JOIN diagnosis d ON d.attempt_id=a.id
                    WHERE a.user_id=:user AND (d.completed_at>=latest.aborted_at OR
                        (a.started_at>latest.aborted_at AND a.aborted_at IS NULL AND d.id IS NULL))
                )
                """).param("user", user).param("lower", evening.minusDays(2)).param("upper", evening.minusDays(1))
                .query(String.class).optional();
    }

    private java.util.Optional<String> periodic(UUID user, OffsetDateTime evening) {
        return db.sql("""
                SELECT id::text FROM (
                    SELECT d.id,d.completed_at FROM diagnosis d JOIN survey_attempt a ON a.id=d.attempt_id
                    WHERE a.user_id=:user ORDER BY d.completed_at DESC,d.id DESC LIMIT 1
                ) latest WHERE completed_at>:lower AND completed_at<=:upper
                """).param("user", user).param("lower", evening.minusDays(31)).param("upper", evening.minusDays(30))
                .query(String.class).optional();
    }

    private java.util.List<String> guides(UUID user, OffsetDateTime evening) {
        return db.sql("""
                SELECT g.content_id FROM guide_activity g
                JOIN published_guide p ON p.content_key=g.content_id AND g.representative_type=ANY(p.types)
                WHERE g.user_id=:user AND g.completed_at IS NULL AND g.viewed_at>:lower AND g.viewed_at<=:upper
                AND NOT EXISTS (SELECT 1 FROM guide_activity newer WHERE newer.user_id=g.user_id
                    AND newer.content_id=g.content_id AND newer.month_start>g.month_start)
                ORDER BY g.viewed_at,g.content_id
                """).param("user", user).param("lower", evening.minusDays(4)).param("upper", evening.minusDays(3))
                .query(String.class).list();
    }

    private void add(UUID user, Template template, String key, OffsetDateTime now) {
        var id = UUID.randomUUID();
        var inserted = db.sql("""
                INSERT INTO app_notification(id,user_id,kind,event_key,title,body,created_at,template_id,template_version)
                VALUES (:id,:user,:kind,:key,:title,:body,:now,:template,'1.0')
                ON CONFLICT(user_id,kind,event_key) DO NOTHING
                """).param("id", id).param("user", user).param("kind", template.name()).param("key", key)
                .param("template", template.id).param("title", template.title).param("body", template.body).param("now", now).update();
        if (inserted == 0) return;
        var category = switch (template) {
            case ECONOMY, EXPORT -> template.name();
            default -> "REMINDER";
        };
        var reserved = db.sql("""
                INSERT INTO app_notification_local_slot(notification_id,user_id,delivery_day,category)
                SELECT :id,:user,:day,:category WHERE notification_slot_available(:user,:day,:category)
                """).param("id", id).param("user", user).param("day", now.toLocalDate())
                .param("category", category).update();
        if (reserved == 1) {
            var deadline = switch (template) {
                case EXPORT -> now.plusHours(1);
                case ECONOMY -> now.withHour(12).withMinute(0).withSecond(0).withNano(0);
                default -> now.withHour(22).withMinute(0).withSecond(0).withNano(0);
            };
            pushQueue.enqueue(id, now, deadline);
        }
    }

    boolean eligibleForDelivery(UUID notification, UUID user, String kind, String key, OffsetDateTime created, OffsetDateTime at) {
        var now = at.atZoneSameInstant(KST).toOffsetDateTime();
        var date = created.atZoneSameInstant(KST).toLocalDate();
        var morning = date.atTime(MORNING).atZone(KST).toOffsetDateTime();
        var evening = date.atTime(EVENING).atZone(KST).toOffsetDateTime();
        var allowed = preference(user).orElse(null);
        if (allowed == null) return false;
        if (kind.equals("NOTICE")) {
            if (now.toLocalTime().isBefore(LocalTime.of(9, 0)) || !now.toLocalTime().isBefore(LocalTime.of(20, 0))) return false;
            return db.sql("""
                    SELECT EXISTS(SELECT 1 FROM service_notice_revision r
                        JOIN service_notice n ON n.published_revision_id=r.id
                        CROSS JOIN operating_settings o
                        WHERE r.id::text=:key AND n.kind='NOTICE' AND r.status='PUBLISHED'
                        AND r.starts_at<=:now AND (r.ends_at IS NULL OR r.ends_at>:now)
                        AND NOT o.crm_paused
                        AND EXISTS(SELECT 1 FROM campaign_local_delivery d JOIN campaign c ON c.id=d.campaign_id
                            JOIN admin_account a ON a.id=c.actor_id AND a.status='ACTIVE'
                            JOIN admin_role role ON role.admin_id=a.id AND role.role='OPERATOR'
                            WHERE d.notification_id=:notification AND c.status IN ('SENDING','COMPLETED')
                            AND c.scheduled_at<=:now
                            AND (o.crm_resumed_at IS NULL OR o.crm_resumed_at<=c.scheduled_at)))
                    """).param("notification", notification).param("key", key).param("now", now)
                    .query(Boolean.class).single();
        }
        var scheduled = switch (kind) {
            case "ECONOMY" -> morning;
            case "EXPORT" -> created;
            default -> evening;
        };
        if (!policy().permits(scheduled)) return false;
        return switch (kind) {
            case "INCOMPLETE" -> allowed.incomplete() && incomplete(user, evening).filter(key::equals).isPresent();
            case "PERIODIC" -> allowed.periodic() && periodic(user, evening).filter(key::equals).isPresent();
            case "GUIDE" -> allowed.guide() && guides(user, evening).stream()
                    .anyMatch(content -> key.equals(content + ":" + java.time.YearMonth.from(date)));
            case "ECONOMY" -> allowed.economy() && economyEligible(user, now, morning);
            case "EXPORT" -> db.sql("""
                    SELECT EXISTS(SELECT 1 FROM app_data_export WHERE id::text=:key AND user_id=:user
                        AND status='COMPLETED' AND archive IS NOT NULL AND expires_at>:now)
                    """).param("key", key).param("user", user).param("now", now).query(Boolean.class).single();
            default -> false;
        };
    }
}
