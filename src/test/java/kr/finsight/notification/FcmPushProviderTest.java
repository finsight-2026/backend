package kr.finsight.notification;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FcmPushProviderTest {
    private record Reply(int status, String body, String retryAfter) {}
    private record Request(String method, String authorization, JsonNode body) {}

    private final JsonMapper json = new JsonMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final AtomicReference<Request> request = new AtomicReference<>();
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/projects/test-finsight/messages:send", exchange -> {
            try (exchange) {
                request.set(new Request(exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        json.readTree(exchange.getRequestBody().readAllBytes())));
                var output = reply.get();
                if (output.retryAfter() != null) exchange.getResponseHeaders().set("Retry-After", output.retryAfter());
                var bytes = output.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(output.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1/projects/test-finsight/messages:send");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsOnlyTheSpecifiedDataAndMapsApnsExpiryAndSound() {
        var message = message(true);
        reply.set(new Reply(200, "{\"name\":\"projects/test-finsight/messages/fixture\"}", null));
        assertEquals(PushProvider.Outcome.ACCEPTED, provider().send("synthetic-registration", message).outcome());
        assertEquals("POST", request.get().method());
        assertEquals("Bearer synthetic-oauth", request.get().authorization());
        var sent = request.get().body().path("message");
        var data = sent.path("data");
        assertEquals(6, data.size());
        assertEquals(message.notificationId().toString(), data.path("notification_id").asString());
        assertEquals("NTF-05", data.path("template_id").asString());
        assertEquals("1.0", data.path("template_version").asString());
        assertEquals("EXPORT", data.path("destination_type").asString());
        assertEquals(message.resourceId(), data.path("resource_id").asString());
        assertEquals(message.expiresAt().toInstant().toString(), data.path("expires_at").asString());
        assertEquals(Long.toString(message.expiresAt().toEpochSecond()),
                sent.at("/apns/headers/apns-expiration").asString());
        assertEquals(message.notificationId().toString(), sent.at("/apns/headers/apns-collapse-id").asString());
        assertEquals(message.title(), sent.at("/apns/payload/aps/alert/title").asString());
        assertTrue(sent.at("/apns/payload/aps/sound").isMissingNode());
        provider().send("synthetic-registration", message(false));
        assertEquals("default", request.get().body().at("/message/apns/payload/aps/sound").asString());
    }

    @Test
    void distinguishesInvalidRegistrationTransientRejectionAndUnknownAcceptance() {
        var provider = provider();
        var message = message(false);
        reply.set(new Reply(404, """
                {"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",
                  "errorCode":"UNREGISTERED"}]}}
                """, null));
        assertEquals(PushProvider.Outcome.INVALID, provider.send("test", message).outcome());
        reply.set(new Reply(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}", null));
        assertEquals(PushProvider.Outcome.REJECTED, provider.send("test", message).outcome());
        reply.set(new Reply(429, "{}", "1"));
        assertEquals(PushProvider.Result.retry(OffsetDateTime.now(clock).plusMinutes(1)), provider.send("test", message));
        reply.set(new Reply(503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}", "Wed, 11 Nov 2026 12:02:00 GMT"));
        assertEquals(PushProvider.Result.retry(OffsetDateTime.now(clock).plusMinutes(2)), provider.send("test", message));
        reply.set(new Reply(500, "unexpected gateway error", null));
        assertEquals(PushProvider.Outcome.UNKNOWN, provider.send("test", message).outcome());
        reply.set(new Reply(200, "{}", null));
        assertEquals(PushProvider.Outcome.UNKNOWN, provider.send("test", message).outcome());
    }

    @Test
    void targetsAnInstallationIdWithoutSendingALegacyToken() {
        reply.set(new Reply(200, """
                {"name":"projects/test-finsight/messages/installation-fixture"}
                """, null));
        var provider = provider().forInstallations();
        assertEquals(PushRegistrations.Provider.FCM_FID, provider.provider());
        assertEquals(PushProvider.Outcome.ACCEPTED, provider.send("synthetic-installation", message(false)).outcome());
        var sent = request.get().body().path("message");
        assertEquals("synthetic-installation", sent.path("fid").asString());
        assertTrue(sent.path("token").isMissingNode());
    }

    private FcmPushProvider provider() {
        return new FcmPushProvider(HttpClient.newHttpClient(), endpoint, () -> "synthetic-oauth", json, clock);
    }

    private PushProvider.Message message(boolean silent) {
        return new PushProvider.Message(UUID.randomUUID(), "NTF-05", "1.0", "EXPORT", UUID.randomUUID().toString(),
                "요청하신 파일이 준비됐어요", "앱에서 파일을 내려받을 수 있어요.",
                OffsetDateTime.now(clock).plusMinutes(5), silent);
    }
}
