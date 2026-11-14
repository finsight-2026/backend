package kr.finsight.account;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MailLinkOutboundMailTest {
    private record Request(String path, String authorization, JsonNode body) {}
    private final JsonMapper json = new JsonMapper();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private MailLinkOutboundMail mail;
    private volatile String tokenReply = """
            {"return_code":0,"token":"synthetic-token"}
            """;
    private volatile String sendReply = """
            {"return_code":"0","job_count":1}
            """;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                var path = exchange.getRequestURI().getPath();
                requests.add(new Request(path, exchange.getRequestHeaders().getFirst("Authorization"),
                        json.readTree(exchange.getRequestBody().readAllBytes())));
                var bytes = (path.equals("/auth/token") ? tokenReply : sendReply).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        var origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        mail = new MailLinkOutboundMail(HttpClient.newHttpClient(), json, origin,
                "fixture-key", "fixture-secret", "AU-100", "AU-101", "AU-102", "AU-103");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void usesSeparateTemplatesAndSendsOnlyOneRecipientPerMessage() {
        mail.deliver("signup@example.test", "001234");
        mail.deliverReset("reset@example.test", "https://finsight.life/password-reset#token=fixture");
        assertEquals(List.of("/auth/token", "/automation/email", "/auth/token", "/automation/email"),
                requests.stream().map(Request::path).toList());
        assertEquals("fixture-key", requests.get(0).body().path("appkey").asString());
        assertEquals("fixture-secret", requests.get(0).body().path("appsecret").asString());
        assertNull(requests.get(0).authorization());
        assertRecipient(requests.get(1), "AU-100", "signup@example.test", "code", "001234");
        assertRecipient(requests.get(3), "AU-101", "reset@example.test", "reset_link",
                "https://finsight.life/password-reset#token=fixture");
        assertNotEquals(requests.get(1).body().path("list").get(0).path("data_key").asString(),
                requests.get(3).body().path("list").get(0).path("data_key").asString());
    }

    @Test
    void sendsAdminLinksWithDedicatedTemplates() {
        var invite = "https://admin.finsight.life/#admin-invitation=fixture";
        var reset = "https://admin.finsight.life/#admin-password-reset=fixture";
        mail.deliverAdminInvitation("invite@example.test", invite);
        mail.deliverAdminPasswordReset("reset@example.test", reset);
        assertEquals(4, requests.size());
        assertRecipient(requests.get(1), "AU-102", "invite@example.test", "invitation_link", invite);
        assertRecipient(requests.get(3), "AU-103", "reset@example.test", "reset_link", reset);
    }

    @Test
    void disabledOrReusedAdminTemplatesDoNotSendMail() {
        var http = HttpClient.newHttpClient();
        var origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        assertThrows(IllegalArgumentException.class, () -> new MailLinkOutboundMail(http, json, origin,
                "fixture-key", "fixture-secret", "AU-100", "AU-101", "AU-101", ""));
        var disabled = new MailLinkOutboundMail(http, json, origin,
                "fixture-key", "fixture-secret", "AU-100", "AU-101", "", "");
        assertThrows(MailDeliveryFailure.class, () -> disabled.deliverAdminInvitation("invite@example.test", "unused"));
        assertThrows(MailDeliveryFailure.class, () -> disabled.deliverAdminPasswordReset("reset@example.test", "unused"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void tokenFailureDoesNotSendMailAndUnqueuedMailIsNotRetried() {
        tokenReply = "{\"return_code\":1,\"return_msg\":\"rejected\"}";
        assertThrows(MailDeliveryFailure.class, () -> mail.deliver("signup@example.test", "123456"));
        assertEquals(1, requests.size());
        tokenReply = "{\"return_code\":\"0\",\"token\":\"fixture\"}";
        sendReply = "{\"return_code\":0,\"job_count\":0}";
        assertThrows(MailDeliveryFailure.class, () -> mail.deliverReset("reset@example.test", "unused"));
        assertEquals(3, requests.size());
    }

    private static void assertRecipient(Request request, String template, String email, String variable, String value) {
        assertEquals("Bearer synthetic-token", request.authorization());
        assertEquals(template, request.body().path("automail_id").asString());
        var recipients = request.body().path("list");
        assertEquals(1, recipients.size());
        var recipient = recipients.get(0);
        assertEquals(email, recipient.path("email").asString());
        assertTrue(recipient.path("data_key").asString().matches("[a-f0-9]{30}"));
        assertEquals(1, recipient.path("variables").size());
        var entry = recipient.path("variables").get(0);
        assertEquals(variable, entry.path("var").asString());
        assertEquals(value, entry.path("value").asString());
    }
}
