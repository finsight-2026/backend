package kr.finsight.account;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.admin.AdminInvitationMail;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("local")
@ConditionalOnProperty(name = "finsight.mail.mode", havingValue = "maillink")
class MailLinkOutboundMail implements SignupMail, PasswordReset.Mail, AdminInvitationMail {
    private static final URI ORIGIN = URI.create("https://api.maillink.co.kr");
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(MailLinkOutboundMail.class);
    private final HttpClient http;
    private final JsonMapper json;
    private final URI origin;
    private final String appKey;
    private final String appSecret;
    private final String signupTemplate;
    private final String resetTemplate;
    private final String adminInvitationTemplate;
    private final String adminResetTemplate;

    @Autowired
    MailLinkOutboundMail(JsonMapper json,
                         @Value("${FINSIGHT_MAILLINK_APP_KEY:}") String appKey,
                         @Value("${FINSIGHT_MAILLINK_APP_SECRET:}") String appSecret,
                         @Value("${FINSIGHT_MAILLINK_SIGNUP_TEMPLATE:}") String signupTemplate,
                         @Value("${FINSIGHT_MAILLINK_RESET_TEMPLATE:}") String resetTemplate,
                         @Value("${FINSIGHT_MAILLINK_ADMIN_INVITATION_TEMPLATE:}") String adminInvitationTemplate,
                         @Value("${FINSIGHT_MAILLINK_ADMIN_RESET_TEMPLATE:}") String adminResetTemplate) {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), json, ORIGIN,
                appKey, appSecret, signupTemplate, resetTemplate, adminInvitationTemplate, adminResetTemplate);
    }

    MailLinkOutboundMail(HttpClient http, JsonMapper json, URI origin, String appKey, String appSecret,
                         String signupTemplate, String resetTemplate, String adminInvitationTemplate,
                         String adminResetTemplate) {
        if (appKey.isBlank() || appSecret.isBlank()) {
            throw new IllegalArgumentException("MailLink 앱 키와 시크릿이 필요합니다.");
        }
        if (!signupTemplate.matches("AU-[0-9]+") || !resetTemplate.matches("AU-[0-9]+")
                || signupTemplate.equals(resetTemplate)) {
            throw new IllegalArgumentException("가입과 재설정에 각각 MailLink 자동메일 템플릿 ID가 필요합니다.");
        }
        var templates = new HashSet<>(List.of(signupTemplate, resetTemplate));
        for (String template : List.of(adminInvitationTemplate, adminResetTemplate)) {
            if (!template.isBlank() && (!template.matches("AU-[0-9]+") || !templates.add(template))) {
                throw new IllegalArgumentException("관리자 초대와 재설정에는 각각 고유한 MailLink 자동메일 템플릿 ID가 필요합니다.");
            }
        }
        this.http = http;
        this.json = json;
        this.origin = origin;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.signupTemplate = signupTemplate;
        this.resetTemplate = resetTemplate;
        this.adminInvitationTemplate = adminInvitationTemplate;
        this.adminResetTemplate = adminResetTemplate;
    }

    @Override
    public void deliver(String email, String code) {
        if (email.length() > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일 주소는 60자까지 입력할 수 있어요.");
        }
        send(signupTemplate, email, "code", code);
    }

    @Override
    public void deliverReset(String email, String link) {
        if (email.length() > 60) throw failed("/automation/email");
        send(resetTemplate, email, "reset_link", link);
    }

    @Override
    public void deliverAdminInvitation(String email, String link) {
        if (adminInvitationTemplate.isBlank() || email.length() > 60) throw new MailDeliveryFailure();
        send(adminInvitationTemplate, email, "invitation_link", link);
    }

    @Override
    public void deliverAdminPasswordReset(String email, String link) {
        if (adminResetTemplate.isBlank() || email.length() > 60) throw new MailDeliveryFailure();
        send(adminResetTemplate, email, "reset_link", link);
    }

    private void send(String template, String email, String variable, String value) {
        var tokenResponse = post("/auth/token", null, Map.of("appkey", appKey, "appsecret", appSecret));
        var token = tokenResponse.path("token");
        if (!token.isString() || !token.asString().matches("[A-Za-z0-9._~+/-]+=*")) {
            throw failed("/auth/token");
        }
        var recipient = Map.of(
                "data_key", UUID.randomUUID().toString().replace("-", "").substring(0, 30),
                "email", email,
                "variables", List.of(Map.of("var", variable, "value", value)));
        var result = post("/automation/email", token.asString(),
                Map.of("automail_id", template, "list", List.of(recipient)));
        if (!"1".equals(result.path("job_count").asString())) throw failed("/automation/email");
    }

    private JsonNode post(String path, String token, Map<String, ?> body) {
        try {
            var request = HttpRequest.newBuilder(origin.resolve(path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (token != null) request.header("Authorization", "Bearer " + token);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw failed(path);
            var result = json.readTree(response.body());
            if (result == null || !"0".equals(result.path("return_code").asString())) throw failed(path);
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failed(path);
        } catch (IOException | tools.jackson.core.JacksonException error) {
            throw failed(path);
        }
    }

    private static MailDeliveryFailure failed(String path) {
        LOG.warn("MailLink 요청 실패 [{}]", path);
        return new MailDeliveryFailure();
    }
}
