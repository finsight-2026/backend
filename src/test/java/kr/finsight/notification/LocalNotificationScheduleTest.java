package kr.finsight.notification;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kr.finsight.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LocalNotificationScheduleTest {
    @Autowired JdbcClient db;
    @Autowired TransactionTemplate transactions;

    @Test
    void selectsEligibleEveningEventsOnceAndReservesAtMostTwoSlots() {
        var user = account(true);
        var schedule = new LocalNotificationSchedule(db, transactions, new PushQueue(db));
        var evening = OffsetDateTime.parse("2026-11-10T20:00:00+09:00");
        var diagnosisAttempt = attempt(user, evening.minusDays(30).minusHours(1), null);
        db.sql("""
                INSERT INTO diagnosis(id,attempt_id,completed_at,representative_type,score_snapshot)
                VALUES (gen_random_uuid(),:attempt,:completed,'DEBT','{}')
                """).param("attempt", diagnosisAttempt).param("completed", evening.minusDays(30)).update();
        attempt(user, evening.minusDays(1).minusHours(2), evening.minusDays(1).minusHours(1));
        guide(user, evening.minusDays(3), "DEBT-1", false);
        guide(user, evening.minusDays(3), "DEBT-2", true);
        guide(user, evening.minusDays(3), "guide-unpublished", false);
        db.sql("""
                INSERT INTO app_data_export(id,user_id,status,requested_at,expires_at,archive,source_epoch)
                VALUES (gen_random_uuid(),:user,'COMPLETED',:requested,:expires,decode('00','hex'),0)
                """).param("user", user).param("requested", evening.minusMinutes(5))
                .param("expires", evening.plusHours(24)).update();

        schedule.process(user, evening.minusSeconds(1));
        assertEquals(List.of(), kinds(user));
        schedule.process(user, evening);
        schedule.process(user, evening.plusMinutes(1));
        assertEquals(List.of("EXPORT", "GUIDE", "INCOMPLETE", "PERIODIC"), kinds(user));
        assertEquals(List.of("EXPORT", "INCOMPLETE"), reservations(user));
        assertEquals("처음부터 다시 시작해볼까요?", db.sql("""
                SELECT title FROM app_notification WHERE user_id=:user AND kind='INCOMPLETE'
                """).param("user", user).query(String.class).single());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void pausedAutomaticWindowsAreSkippedAndConfiguredCapsApplyToLaterWindows() {
        var user = account(true);
        var schedule = new LocalNotificationSchedule(db, transactions, new PushQueue(db));
        var first = OffsetDateTime.parse("2026-11-10T20:00:00+09:00");
        var next = first.plusDays(1);
        attempt(user, first.minusDays(1).minusHours(2), first.minusDays(1).minusHours(1));
        db.sql("UPDATE operating_settings SET automatic_push_paused=true WHERE singleton").update();
        schedule.process(user, first);
        assertTrue(kinds(user).isEmpty());
        db.sql("""
                UPDATE operating_settings SET automatic_push_paused=false,automatic_push_resumed_at=:resumed,
                    reminder_cap=0,total_cap=1 WHERE singleton
                """).param("resumed", first.plusMinutes(1)).update();
        schedule.process(user, first.plusMinutes(2));
        assertTrue(kinds(user).isEmpty());

        attempt(user, next.minusDays(1).minusHours(2), next.minusDays(1).minusHours(1));
        db.sql("""
                INSERT INTO app_data_export(id,user_id,status,requested_at,expires_at,archive,source_epoch)
                VALUES (gen_random_uuid(),:user,'COMPLETED',:requested,:expires,decode('00','hex'),0)
                """).param("user", user).param("requested", next.minusMinutes(1))
                .param("expires", next.plusHours(24)).update();
        schedule.process(user, next);
        assertEquals(List.of("EXPORT", "INCOMPLETE"), kinds(user));
        assertEquals(List.of("EXPORT"), reservations(user));
    }

    @Test
    void excludesDisabledAccountsOngoingAttemptsAndExpiredWindows() {
        var now = OffsetDateTime.parse("2026-11-10T20:15:00+09:00");
        var disabled = account(false);
        var withdrawn = account(true);
        var ongoing = account(true);
        var expired = account(true);
        var schedule = new LocalNotificationSchedule(db, transactions, new PushQueue(db));
        for (var user : List.of(disabled, withdrawn, ongoing, expired)) {
            attempt(user, now.minusDays(1).minusHours(2), now.minusDays(1).minusHours(1));
        }
        db.sql("UPDATE app_user SET status='WITHDRAWAL_PENDING' WHERE id=:user")
                .param("user", withdrawn).update();
        attempt(ongoing, now.minusHours(1), null);
        schedule.process(disabled, now);
        schedule.process(withdrawn, now);
        schedule.process(ongoing, now);
        schedule.process(expired, now.plusHours(2));
        for (var user : List.of(disabled, withdrawn, ongoing, expired)) assertTrue(kinds(user).isEmpty());
    }

    @Test
    void economyRequiresAVisibleEditionPublishedByEight() {
        var now = OffsetDateTime.parse("2026-11-10T08:00:00+09:00");
        var user = account(true);
        var schedule = new LocalNotificationSchedule(db, transactions, new PushQueue(db));
        var admin = UUID.randomUUID();
        var article = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'fixture','test',decode('00','hex'),'ACTIVE')
                """).param("id", admin).param("email", admin + "@example.test").update();
        db.sql("INSERT INTO news_article(id,source_url) VALUES (:id,:url)")
                .param("id", article).param("url", "https://www.hankyung.com/article/" + article).update();
        db.sql("""
                INSERT INTO news_revision(article_id,revision,author_id,status,title,summary,body,reason,action,
                    article_published_at,types,starts_at,updated_at)
                VALUES (:id,1,:admin,'PUBLISHED','fixture','summary','body','reason','action',
                    :time,ARRAY['DEBT'],:time,:time)
                """).param("id", article).param("admin", admin).param("time", now.plusMinutes(1)).update();
        db.sql("UPDATE news_article SET published_revision=1 WHERE id=:id").param("id", article).update();
        db.sql("""
                INSERT INTO news_edition_item(edition_date,type,slot,article_id,article_revision,assigned_at)
                VALUES (:day,'DEBT',1,:id,1,:assigned)
                """)
                .param("day", now.toLocalDate()).param("id", article).param("assigned", now.plusMinutes(1)).update();
        schedule.process(user, now.plusMinutes(30));
        assertTrue(kinds(user).isEmpty());
        db.sql("UPDATE news_revision SET starts_at=:time WHERE article_id=:id")
                .param("time", now.minusMinutes(1)).param("id", article).update();
        schedule.process(user, now.plusMinutes(30));
        assertTrue(kinds(user).isEmpty());
        db.sql("UPDATE news_edition_item SET assigned_at=:time WHERE article_id=:id")
                .param("time", now.minusMinutes(1)).param("id", article).update();
        schedule.process(user, now.plusMinutes(30));
        assertTrue(kinds(user).isEmpty());
        db.sql("UPDATE news_revision SET updated_at=:time WHERE article_id=:id")
                .param("time", now.minusMinutes(1)).param("id", article).update();
        db.sql("""
                INSERT INTO news_revision(article_id,revision,author_id,status,title,summary,body,reason,action,article_published_at,types)
                SELECT article_id,2,author_id,'APPROVED',title,summary,body,reason,action,article_published_at,types
                FROM news_revision WHERE article_id=:id AND revision=1
                """).param("id", article).update();
        db.sql("UPDATE news_edition_item SET article_revision=2 WHERE article_id=:id")
                .param("id", article).update();
        schedule.process(user, now.plusMinutes(1));
        assertTrue(kinds(user).isEmpty());
        db.sql("UPDATE news_edition_item SET article_revision=1 WHERE article_id=:id")
                .param("id", article).update();
        schedule.process(user, now.minusSeconds(1));
        assertTrue(kinds(user).isEmpty());
        schedule.process(user, now);
        schedule.process(user, now.plusMinutes(1));
        assertEquals(List.of("ECONOMY"), kinds(user));
        assertEquals(List.of("ECONOMY"), reservations(user));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void reservedPushesRevalidateConsentAndDeviceAndNeverRetryAmbiguousSends() {
        var now = OffsetDateTime.parse("2026-11-10T20:00:00+09:00");
        var user = account(true);
        attempt(user, now.minusDays(1).minusHours(2), now.minusDays(1).minusHours(1));
        var first = device(user, now);
        var second = device(user, now);
        var third = device(user, now);
        var queue = new PushQueue(db);
        var schedule = new LocalNotificationSchedule(db, transactions, queue);
        var dispatch = new PushDispatch(db, transactions, schedule, List.of());
        schedule.process(user, now);
        schedule.process(user, now.plusSeconds(1));
        assertEquals(List.of("WAITING", "WAITING", "WAITING"), states(user));

        db.sql("DELETE FROM app_session WHERE id=:session").param("session", third).update();
        var messages = new java.util.ArrayList<PushProvider.Message>();
        var provider = new PushProvider() {
            public PushRegistrations.Provider provider() { return PushRegistrations.Provider.LOCAL; }
            public Result send(String token, Message message) {
                messages.add(message);
                if (token.equals(first.toString())) return Result.of(Outcome.RETRY);
                throw new IllegalStateException("Ambiguous delivery");
            }
        };
        for (int i = 0; i < 3; i++) assertTrue(dispatch.process(provider, now));
        assertFalse(dispatch.process(provider, now.plusSeconds(29)));
        assertEquals(List.of("CANCELED", "UNKNOWN", "WAITING"), states(user));
        assertEquals(2, messages.size());
        assertEquals(messages.get(0).notificationId(), messages.get(1).notificationId());
        assertEquals("NTF-02", messages.getFirst().templateId());
        assertEquals("DIAGNOSIS", messages.getFirst().destinationType());
        assertEquals(now.plusHours(2), messages.getFirst().expiresAt().withOffsetSameInstant(now.getOffset()));

        db.sql("UPDATE app_notification_preference SET incomplete=false WHERE user_id=:user")
                .param("user", user).update();
        assertTrue(dispatch.process(provider, now.plusSeconds(30)));
        assertFalse(dispatch.process(provider, now.plusSeconds(31)));
        assertEquals(2, messages.size());
        assertEquals(List.of("CANCELED", "CANCELED", "UNKNOWN"), states(user));
        db.sql("DELETE FROM app_session WHERE id=:id").param("id", second).update();
        assertEquals(List.of("CANCELED", "CANCELED", "UNKNOWN"), states(user));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void retriesAnExplicitTransientFailureWithTheSameNotificationId() {
        var now = OffsetDateTime.parse("2026-11-10T20:00:00+09:00");
        var user = account(true);
        attempt(user, now.minusDays(1).minusHours(2), now.minusDays(1).minusHours(1));
        device(user, now);
        var schedule = new LocalNotificationSchedule(db, transactions, new PushQueue(db));
        var dispatch = new PushDispatch(db, transactions, schedule, List.of());
        schedule.process(user, now);
        var messages = new java.util.ArrayList<PushProvider.Message>();
        var provider = new PushProvider() {
            public PushRegistrations.Provider provider() { return PushRegistrations.Provider.LOCAL; }
            public Result send(String token, Message message) {
                messages.add(message);
                return messages.size() == 1 ? Result.retry(now.plusSeconds(75)) : Result.of(Outcome.SIMULATED);
            }
        };
        assertTrue(dispatch.process(provider, now));
        assertFalse(dispatch.process(provider, now.plusSeconds(74)));
        assertTrue(dispatch.process(provider, now.plusSeconds(75)));
        assertFalse(dispatch.process(provider, now.plusSeconds(76)));
        assertEquals(2, messages.size());
        assertEquals(messages.get(0).notificationId(), messages.get(1).notificationId());
        assertEquals(List.of("SIMULATED"), states(user));
        assertEquals(0, db.sql("SELECT count(*) FROM app_notification WHERE user_id=:user AND read_at IS NOT NULL")
                .param("user", user).query(Integer.class).single());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void crmDeviceDeliveryUsesThreeRetriesAndStopsAtConfirmedFailure() {
        var now = OffsetDateTime.parse("2026-11-10T10:00:00+09:00");
        var user = account(true);
        var device = device(user, now);
        var actor = UUID.randomUUID();
        var notice = UUID.randomUUID();
        var revision = UUID.randomUUID();
        var campaign = UUID.randomUUID();
        var notification = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'운영자','test',decode('00','hex'),'ACTIVE')
                """).param("id", actor).param("email", actor + "@example.test").update();
        db.sql("INSERT INTO admin_role(admin_id,role) VALUES (:actor,'OPERATOR')").param("actor", actor).update();
        db.sql("INSERT INTO service_notice(id,kind) VALUES (:id,'NOTICE')").param("id", notice).update();
        db.sql("""
                INSERT INTO service_notice_revision(id,notice_id,revision_no,author_id,status,title,summary,body,sort_order,starts_at)
                VALUES (:id,:notice,1,:actor,'PUBLISHED','안내','안내','안내',0,:start)
                """).param("id", revision).param("notice", notice).param("actor", actor)
                .param("start", now.minusMinutes(1)).update();
        db.sql("UPDATE service_notice SET published_revision_id=:revision WHERE id=:notice")
                .param("revision", revision).param("notice", notice).update();
        db.sql("""
                INSERT INTO campaign(id,actor_id,name,notice_revision_id,template_id,template_version,
                    title_snapshot,body_snapshot,destination,scheduled_at,status)
                VALUES (:id,:actor,'점검',:revision,'NTF-06','1.0','안내','안내','B_003',:now,'COMPLETED')
                """).param("id", campaign).param("actor", actor).param("revision", revision).param("now", now).update();
        db.sql("""
                INSERT INTO app_notification(id,user_id,kind,event_key,title,body,created_at,template_id,template_version)
                VALUES (:id,:user,'NOTICE',:key,'안내','안내',:now,'NTF-06','1.0')
                """).param("id", notification).param("user", user).param("key", revision.toString()).param("now", now).update();
        db.sql("""
                INSERT INTO campaign_local_delivery(id,campaign_id,notice_revision_id,user_id,status,notification_id)
                VALUES (gen_random_uuid(),:campaign,:revision,:user,'LOCAL_INBOX',:notification)
                """).param("campaign", campaign).param("revision", revision).param("user", user)
                .param("notification", notification).update();
        db.sql("""
                INSERT INTO app_push_delivery(notification_id,session_id,provider,next_at,expires_at)
                VALUES (:id,:device,'LOCAL',:now,:deadline)
                """).param("id", notification).param("device", device).param("now", now)
                .param("deadline", now.plusHours(2)).update();
        var schedule = new LocalNotificationSchedule(db, transactions, new PushQueue(db));
        var dispatch = new PushDispatch(db, transactions, schedule, List.of());
        var messages = new java.util.ArrayList<UUID>();
        var provider = new PushProvider() {
            public PushRegistrations.Provider provider() { return PushRegistrations.Provider.LOCAL; }
            public Result send(String token, Message message) {
                messages.add(message.notificationId());
                return Result.of(Outcome.RETRY);
            }
        };
        assertTrue(dispatch.process(provider, now));
        for (int minutes : List.of(1, 6, 21)) {
            assertFalse(dispatch.process(provider, now.plusMinutes(minutes).minusSeconds(1)));
            assertTrue(dispatch.process(provider, now.plusMinutes(minutes)));
        }
        assertEquals(List.of(notification, notification, notification, notification), messages);
        assertEquals(List.of("FAILED"), states(user));
        assertFalse(dispatch.process(provider, now.plusHours(1)));
    }

    private UUID device(UUID user, OffsetDateTime now) {
        var session = UUID.randomUUID();
        db.sql("""
                INSERT INTO app_session(id,user_id,refresh_hash,expires_at) VALUES (:id,:user,:hash,:expires)
                """).param("id", session).param("user", user).param("hash", session.toString())
                .param("expires", now.plusDays(1)).update();
        db.sql("""
                INSERT INTO app_push_registration(session_id,provider,registration_token) VALUES (:id,'LOCAL',:token)
                """).param("id", session).param("token", session.toString()).update();
        return session;
    }

    private List<String> states(UUID user) {
        return db.sql("""
                SELECT p.state FROM app_push_delivery p JOIN app_notification n ON n.id=p.notification_id
                WHERE n.user_id=:user ORDER BY p.state
                """).param("user", user).query(String.class).list();
    }

    private UUID account(boolean enabled) {
        var user = UUID.randomUUID();
        db.sql("INSERT INTO app_user(id,email,status) VALUES (:user,:email,'ACTIVE')")
                .param("user", user).param("email", user + "@example.test").update();
        db.sql("""
                INSERT INTO app_notification_preference(user_id,enabled,periodic,incomplete,guide,economy)
                VALUES (:user,:enabled,true,true,true,true)
                """).param("user", user).param("enabled", enabled).update();
        return user;
    }

    private UUID attempt(UUID user, OffsetDateTime started, OffsetDateTime aborted) {
        var id = UUID.randomUUID();
        db.sql("""
                INSERT INTO survey_attempt(id,user_id,survey_version,formula_version,started_at,aborted_at,last_question)
                VALUES (:id,:user,'test','test',:started,:aborted,:lastQuestion)
                """).param("id", id).param("user", user).param("started", started)
                .param("aborted", aborted, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("lastQuestion", aborted == null ? null : 4, java.sql.Types.SMALLINT).update();
        return id;
    }

    private void guide(UUID user, OffsetDateTime viewed, String id, boolean completed) {
        db.sql("""
                INSERT INTO guide_activity(user_id,content_id,month_start,content_version,representative_type,title,viewed_at,completed_at)
                VALUES (:user,:id,:month,1,'DEBT','test',:viewed,:completed)
                """).param("user", user).param("id", id).param("month", viewed.toLocalDate().withDayOfMonth(1))
                .param("viewed", viewed).param("completed", completed ? viewed.plusMinutes(1) : null,
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    private List<String> kinds(UUID user) {
        return db.sql("SELECT kind FROM app_notification WHERE user_id=:user ORDER BY kind")
                .param("user", user).query(String.class).list();
    }

    private List<String> reservations(UUID user) {
        return db.sql("""
                SELECT n.kind FROM app_notification n JOIN app_notification_local_slot s ON s.notification_id=n.id
                WHERE s.user_id=:user ORDER BY n.kind
                """).param("user", user).query(String.class).list();
    }
}
