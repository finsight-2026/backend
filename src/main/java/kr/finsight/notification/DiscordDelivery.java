package kr.finsight.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "finsight.discord.enabled", havingValue = "true")
class DiscordDelivery {
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "finsight.discord.enabled", havingValue = "true")
    static class Scheduling {}

    private static final Logger log = LoggerFactory.getLogger(DiscordDelivery.class);
    private final JdbcClient db;
    private final DiscordOutbox outbox;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    record Message(DiscordOutbox.Event event, UUID id, String content, int attempts) {}

    DiscordDelivery(JdbcClient db, DiscordOutbox outbox, JsonMapper json) {
        this.db = db;
        this.outbox = outbox;
        this.json = json;
    }

    @Scheduled(fixedDelay = 2000)
    void deliverNext() {
        db.sql("""
                UPDATE discord_outbox SET state = 'UNKNOWN', content = NULL
                WHERE state = 'SENDING' AND claimed_at < now() - interval '2 minutes'
                """).update();
        db.sql("""
                UPDATE discord_outbox SET state = 'FAILED', content = NULL
                WHERE state = 'PENDING' AND next_attempt_at < now() - interval '1 day'
                """).update();
        db.sql("""
                UPDATE discord_outbox SET state = 'SENDING', claimed_at = now(), attempts = attempts + 1
                WHERE (event_type, event_id) = (
                    SELECT event_type, event_id FROM discord_outbox
                    WHERE state = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY next_attempt_at, event_type, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                RETURNING event_type,event_id,content,attempts
                """).query((row, index) -> new Message(DiscordOutbox.Event.valueOf(row.getString(1)),
                row.getObject(2, UUID.class), row.getString(3), row.getInt(4)))
                .optional().ifPresent(message -> deliver(message, outbox.destination(message.event())));
    }

    void deliver(Message message, URI destination) {
        if (destination == null) {
            finish(message, "FAILED");
            return;
        }
        var request = HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "content", message.content(), "allowed_mentions", Map.of("parse", List.of()),
                        "flags", 4
                )))).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                finish(message, "DELIVERED");
            } else if (status == 429 && message.attempts() < 5) {
                long delay = response.headers().firstValue("Retry-After").map(DiscordDelivery::retrySeconds).orElse(60L);
                db.sql("""
                        UPDATE discord_outbox SET state = 'PENDING', next_attempt_at = now() + :seconds * interval '1 second'
                        WHERE event_type = :event AND event_id = :id AND state = 'SENDING'
                        """).param("event", message.event().name()).param("id", message.id())
                        .param("seconds", delay).update();
            } else {
                finish(message, status >= 500 ? "UNKNOWN" : "FAILED");
            }
        } catch (IOException failure) {
            finish(message, "UNKNOWN");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish(message, "UNKNOWN");
        }
    }

    private static long retrySeconds(String value) {
        try {
            var seconds = Double.parseDouble(value);
            return Double.isFinite(seconds) ? Math.max(1, (long) Math.ceil(seconds)) : 60;
        } catch (NumberFormatException invalid) {
            return 60;
        }
    }

    private void finish(Message message, String state) {
        db.sql("""
                UPDATE discord_outbox SET state = :state, content = NULL
                WHERE event_type = :event AND event_id = :id AND state = 'SENDING'
                """).param("state", state).param("event", message.event().name()).param("id", message.id()).update();
        if (!"DELIVERED".equals(state)) log.warn("Discord delivery {} for {}", state, message.event());
    }
}
