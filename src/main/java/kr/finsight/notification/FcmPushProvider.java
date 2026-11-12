package kr.finsight.notification;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class FcmPushProvider implements PushProvider {
    @FunctionalInterface
    interface AccessToken {
        String value() throws IOException;
    }

    private final HttpClient http;
    private final URI endpoint;
    private final AccessToken authorization;
    private final JsonMapper json;
    private final Clock clock;
    private final PushRegistrations.Provider registrationType;

    FcmPushProvider(HttpClient http, URI endpoint, AccessToken authorization, JsonMapper json, Clock clock) {
        this(http, endpoint, authorization, json, clock, PushRegistrations.Provider.FCM);
    }

    private FcmPushProvider(HttpClient http, URI endpoint, AccessToken authorization, JsonMapper json,
                            Clock clock, PushRegistrations.Provider registrationType) {
        this.http = http;
        this.endpoint = endpoint;
        this.authorization = authorization;
        this.json = json;
        this.clock = clock;
        this.registrationType = registrationType;
    }

    @Override
    public PushRegistrations.Provider provider() {
        return registrationType;
    }

    FcmPushProvider forInstallations() {
        return new FcmPushProvider(http, endpoint, authorization, json, clock, PushRegistrations.Provider.FCM_FID);
    }

    @Override
    public Result send(String token, Message message) {
        if (!message.expiresAt().toInstant().isAfter(clock.instant())) {
            return Result.of(Outcome.EXPIRED);
        }
        final String bearer;
        try {
            bearer = authorization.value();
        } catch (IOException unavailable) {
            return Result.retry(OffsetDateTime.now(clock).plusMinutes(1));
        }
        if (bearer == null || bearer.isBlank()) return Result.of(Outcome.REJECTED);
        if (!message.expiresAt().toInstant().isAfter(clock.instant())) {
            return Result.of(Outcome.EXPIRED);
        }
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload(token, message))))
                .build();
        try {
            return classify(http.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (IOException uncertain) {
            return Result.of(Outcome.UNKNOWN);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.of(Outcome.UNKNOWN);
        }
    }

    private Map<String, Object> payload(String token, Message message) {
        var alert = Map.of("title", message.title(), "body", message.body());
        var aps = new LinkedHashMap<String, Object>();
        aps.put("alert", alert);
        if (!message.silent()) aps.put("sound", "default");
        var apns = Map.of(
                "headers", Map.of("apns-push-type", "alert",
                        "apns-expiration", Long.toString(message.expiresAt().toEpochSecond()),
                        "apns-collapse-id", message.notificationId().toString()),
                "payload", Map.of("aps", aps));
        var data = Map.of("notification_id", message.notificationId().toString(),
                "template_id", message.templateId(), "template_version", message.templateVersion(),
                "destination_type", message.destinationType(), "resource_id", message.resourceId(),
                "expires_at", message.expiresAt().toInstant().toString());
        var target = registrationType == PushRegistrations.Provider.FCM_FID ? "fid" : "token";
        return Map.of("message", Map.of(target, token, "notification", alert, "data", data, "apns", apns));
    }

    private Result classify(HttpResponse<String> response) {
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (RuntimeException malformed) {
            body = json.createObjectNode();
        }
        if (body == null) body = json.createObjectNode();
        int status = response.statusCode();
        var error = body.path("error");
        if (status >= 200 && status < 300) {
            var accepted = body.path("name").asString("");
            return Result.of(accepted.matches("projects/[^/]+/messages/[^/]+")
                    ? Outcome.ACCEPTED : Outcome.UNKNOWN);
        }
        if (status == 404) {
            for (var detail : error.path("details")) {
                if (detail.path("@type").asString("").equals("type.googleapis.com/google.firebase.fcm.v1.FcmError")
                        && detail.path("errorCode").asString("").equals("UNREGISTERED")) {
                    return Result.of(Outcome.INVALID);
                }
            }
        }
        if (status == 429 || (status == 503 && error.path("status").asString("").equals("UNAVAILABLE"))) {
            var minimum = status == 429 ? Duration.ofMinutes(1) : Duration.ZERO;
            var requested = retryAfter(response.headers().firstValue("Retry-After").orElse(""));
            var delay = requested.compareTo(minimum) > 0 ? requested : minimum;
            return Result.retry(OffsetDateTime.now(clock).plus(delay));
        }
        return Result.of(status >= 400 && status < 500 && status != 408
                ? Outcome.REJECTED : Outcome.UNKNOWN);
    }

    private Duration retryAfter(String value) {
        if (value.isBlank()) return Duration.ZERO;
        Duration delay;
        try {
            delay = Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException notSeconds) {
            try {
                var date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                delay = Duration.between(clock.instant(), date.toInstant());
            } catch (DateTimeParseException invalid) {
                return Duration.ZERO;
            }
        }
        if (delay.isNegative()) return Duration.ZERO;
        var maximum = Duration.ofDays(1);
        return delay.compareTo(maximum) > 0 ? maximum : delay;
    }
}

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "finsight.push.fcm-enabled", havingValue = "true")
class FcmPushConfiguration {
    @Bean
    FcmPushProvider fcmPushProvider(@Value("${finsight.push.fcm-project:}") String project, JsonMapper json)
            throws IOException {
        if (!project.matches("[a-z0-9][a-z0-9-]{4,62}")) {
            throw new IllegalArgumentException("FCM project identifier is required");
        }
        var credentials = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/firebase.messaging");
        var endpoint = URI.create("https://fcm.googleapis.com/v1/projects/" + project + "/messages:send");
        return new FcmPushProvider(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build(),
                endpoint, () -> {
                    credentials.refreshIfExpired();
                    var token = credentials.getAccessToken();
                    return token == null ? null : token.getTokenValue();
                }, json, Clock.systemUTC());
    }

    @Bean
    FcmPushProvider fcmInstallationPushProvider(@Qualifier("fcmPushProvider")
                                                FcmPushProvider fcmPushProvider) {
        return fcmPushProvider.forInstallations();
    }

}
