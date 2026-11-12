package kr.finsight.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.finsight.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DiscordDeliveryTest {
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired TransactionTemplate transaction;

    @Test
    void committedSignupIsQueuedOnceAndDeliveredWithoutMentions() throws Exception {
        var outbox = new DiscordOutbox(db, true, "https://discord.com/api/webhooks/1/test-token", "", "", "");
        var id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            outbox.signedUp(id, "person@example.test");
            status.setRollbackOnly();
        });
        assertEquals(0, db.sql("SELECT count(*) FROM discord_outbox WHERE event_id = :id")
                .param("id", id).query(Integer.class).single());
        transaction.executeWithoutResult(status -> {
            outbox.signedUp(id, "person@example.test");
            outbox.signedUp(id, "person@example.test");
        });
        assertEquals(1, db.sql("SELECT count(*) FROM discord_outbox WHERE event_id = :id")
                .param("id", id).query(Integer.class).single());
        var content = db.sql("SELECT content FROM discord_outbox WHERE event_id = :id")
                .param("id", id).query(String.class).single();
        assertTrue(content.startsWith("[회원가입 완료]가입명수: "));
        assertTrue(content.endsWith("완료 유저: person@example.test"));
        db.sql("UPDATE discord_outbox SET state = 'SENDING' WHERE event_id = :id").param("id", id).update();

        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            try (request) {
                received.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                request.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        try {
            var worker = new DiscordDelivery(db, outbox, json);
            worker.deliver(new DiscordDelivery.Message(DiscordOutbox.Event.SIGNUP, id, content, 1),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        } finally {
            server.stop(0);
        }
        var payload = json.readTree(received.get());
        assertEquals(content, payload.path("content").asString());
        assertTrue(payload.path("allowed_mentions").path("parse").isArray());
        assertEquals(0, payload.path("allowed_mentions").path("parse").size());
        assertEquals("DELIVERED", db.sql("""
                SELECT state FROM discord_outbox WHERE event_id = :id AND content IS NULL
                """).param("id", id).query(String.class).single());
    }

    @Test
    void workerRoutesFiveEventsToFourWebhooks() throws Exception {
        var outbox = spy(new DiscordOutbox(db, true,
                "https://discord.com/api/webhooks/1/signup-token",
                "https://discord.com/api/webhooks/2/withdrawal-token",
                "https://discord.com/api/webhooks/3/survey-token",
                "https://discord.com/api/webhooks/4/news-token"));
        var events = DiscordOutbox.Event.values();
        var paths = List.of("/signup", "/withdrawal", "/withdrawal", "/survey", "/news");
        var webhookIds = List.of(1, 2, 2, 3, 4);
        var received = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            try (request) {
                var payload = json.readTree(request.getRequestBody().readAllBytes());
                synchronized (received) {
                    received.add(request.getRequestMethod() + " " + request.getRequestURI() + " "
                            + payload.path("content").asString());
                }
                request.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var id = UUID.randomUUID();
        try {
            for (int i = 0; i < events.length; i++) {
                var event = events[i];
                var destination = outbox.destination(event);
                assertEquals("discord.com", destination.getHost());
                assertEquals(String.valueOf(webhookIds.get(i)), destination.getPath().split("/")[3]);
                assertEquals("wait=true", destination.getQuery());
                doReturn(URI.create(base + paths.get(i) + "?" + destination.getQuery())).when(outbox).destination(event);
                db.sql("""
                        INSERT INTO discord_outbox(event_type, event_id, content)
                        VALUES (:event, :id, :content)
                        """).param("event", event.name()).param("id", id).param("content", event.name()).update();
            }

            var worker = new DiscordDelivery(db, outbox, json);
            for (var event : events) worker.deliverNext();

            synchronized (received) {
                assertEquals(events.length, received.size());
                for (int i = 0; i < events.length; i++) {
                    assertTrue(received.contains("POST " + paths.get(i) + "?wait=true " + events[i].name()));
                }
            }
            assertEquals(events.length, db.sql("""
                    SELECT count(*) FROM discord_outbox
                    WHERE event_id = :id AND state = 'DELIVERED' AND attempts = 1 AND content IS NULL
                    """).param("id", id).query(Integer.class).single());
        } finally {
            server.stop(0);
            db.sql("DELETE FROM discord_outbox WHERE event_id = :id").param("id", id).update();
        }
    }

    @Test
    void withdrawalAndSurveyUseTheRequestedMessagesAndCountUniqueSurveyUsers() {
        var endpoint = "https://discord.com/api/webhooks/1/test-token";
        var outbox = new DiscordOutbox(db, true, "", endpoint, endpoint, "");
        var user = UUID.randomUUID();
        var request = UUID.randomUUID();
        var email = "survey@example.test";
        transaction.executeWithoutResult(status -> {
            long existingUsers = db.sql("SELECT count(*) FROM app_user").query(Long.class).single();
            long existingCompletions = db.sql("""
                    SELECT count(DISTINCT a.user_id) FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                    """).query(Long.class).single();
            db.sql("INSERT INTO app_user(id,email,status) VALUES (:id,:email,'ACTIVE')")
                    .param("id", user).param("email", email).update();
            for (int i = 0; i < 2; i++) {
                var attempt = UUID.randomUUID();
                var diagnosis = UUID.randomUUID();
                db.sql("""
                        INSERT INTO survey_attempt(id,user_id,survey_version,formula_version,started_at)
                        VALUES (:id,:user,'v1.0','v1.0',now())
                        """).param("id", attempt).param("user", user).update();
                db.sql("""
                        INSERT INTO diagnosis(id,attempt_id,completed_at,representative_type,score_snapshot)
                        VALUES (:id,:attempt,now(),'DEBT','{}')
                        """).param("id", diagnosis).param("attempt", attempt).update();
                outbox.surveyCompleted(diagnosis, user);
                assertEquals("[설문조사 완료]완료명수: %d명,\n완료 유저: %s".formatted(existingCompletions + 1, email),
                        content("SURVEY", diagnosis));
            }
            outbox.withdrawalRequested(request, email);
            assertEquals("[회원탈퇴 신청]\n신청 유저: " + email, content("WITHDRAWAL_REQUESTED", request));
            db.sql("DELETE FROM app_user WHERE id = :id").param("id", user).update();
            outbox.withdrawalCompleted(request, email, 7L);
            assertEquals("""
                    [회원탈퇴 완료] 14일이 지나 탈퇴 처리 완료
                    탈퇴명수:7명, 가입명수:%d명
                    신청 유저: %s""".formatted(existingUsers, email),
                    content("WITHDRAWAL_COMPLETED", request));
            status.setRollbackOnly();
        });
    }

    private String content(String type, UUID id) {
        return db.sql("SELECT content FROM discord_outbox WHERE event_type = :type AND event_id = :id")
                .param("type", type).param("id", id).query(String.class).single();
    }

    @Test
    void sixNewsLinksAreQueuedInOrderOncePerEditionDate() {
        var outbox = new DiscordOutbox(db, true, "", "", "", "https://discord.com/api/webhooks/1/test-token");
        var date = java.time.LocalDate.of(2026, 11, 11);
        var types = kr.finsight.diagnosis.YfsiScoring.Pressure.values();
        var links = new java.util.EnumMap<kr.finsight.diagnosis.YfsiScoring.Pressure, String>(
                kr.finsight.diagnosis.YfsiScoring.Pressure.class);
        for (var type : types) {
            links.put(type, "https://www.hankyung.com/article/" + (100 + type.ordinal()));
        }
        transaction.executeWithoutResult(status -> {
            outbox.newsSelected(date, links);
            outbox.newsSelected(date, links);
            var messages = db.sql("SELECT content FROM discord_outbox WHERE event_type = 'NEWS'")
                    .query(String.class).list();
            assertEquals(List.of("""
                    [오늘의 뉴스 선정 완료]

                    - 부채부담형:https://www.hankyung.com/article/100
                    - 소득불안형:https://www.hankyung.com/article/101
                    - 비상자금취약형:https://www.hankyung.com/article/102
                    - 고정비압박형:https://www.hankyung.com/article/103
                    - 미래불안형:https://www.hankyung.com/article/104
                    - 부양부담형:https://www.hankyung.com/article/105"""), messages);
            status.setRollbackOnly();
        });
    }
}
