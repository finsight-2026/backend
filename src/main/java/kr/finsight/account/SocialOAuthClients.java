package kr.finsight.account;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("local")
class SocialOAuthClients implements SocialProviderGateway {
    private static final String APPLE = "https://appleid.apple.com";
    private record Credentials(String id, String secret) {}

    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Map<SocialAccounts.Provider, Credentials> credentials = new EnumMap<>(SocialAccounts.Provider.class);
    private final URI origin;
    private final JwtDecoder appleTokens;

    SocialOAuthClients(JsonMapper json, Environment env) {
        this.json = json;
        var originSetting = env.getProperty("FINSIGHT_SOCIAL_CALLBACK_ORIGIN", "");
        origin = originSetting.isBlank() ? null : checkedOrigin(originSetting);
        for (var provider : SocialAccounts.Provider.values()) {
            var prefix = "FINSIGHT_SOCIAL_" + provider.name();
            var id = env.getProperty(prefix + "_CLIENT_ID", "");
            var secret = env.getProperty(prefix + "_CLIENT_SECRET", "");
            if (!id.isBlank() && !secret.isBlank()) credentials.put(provider, new Credentials(id, secret));
        }
        if (credentials.containsKey(SocialAccounts.Provider.APPLE) && origin != null) {
            var decoder = NimbusJwtDecoder.withJwkSetUri(APPLE + "/auth/keys").build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(APPLE));
            appleTokens = decoder;
        } else {
            appleTokens = null;
        }
    }

    @Override
    public boolean configured(SocialAccounts.Provider provider) {
        return origin != null && credentials.containsKey(provider);
    }

    @Override
    public URI authorizationUrl(SocialAccounts.Provider provider, String state, boolean reauthenticate) {
        var client = credentials.get(provider);
        var params = new LinkedHashMap<>(Map.of("response_type", "code", "client_id", client.id(),
                "redirect_uri", callback(provider), "state", state));
        String url = switch (provider) {
            case NAVER -> "https://nid.naver.com/oauth2.0/authorize";
            case KAKAO -> {
                if (reauthenticate) params.put("prompt", "login");
                yield "https://kauth.kakao.com/oauth/authorize";
            }
            case APPLE -> {
                params.put("scope", "email");
                params.put("response_mode", "form_post");
                params.put("nonce", AppSessions.hash(state));
                yield APPLE + "/auth/authorize";
            }
        };
        return URI.create(url + "?" + form(params));
    }

    @Override
    public SocialAccounts.VerifiedIdentity exchange(SocialAccounts.Provider provider, String state, String code) {
        var client = credentials.get(provider);
        var params = new LinkedHashMap<>(Map.of("grant_type", "authorization_code", "client_id", client.id(),
                "client_secret", client.secret(), "code", code));
        if (provider == SocialAccounts.Provider.NAVER) {
            params.put("state", state);
        } else {
            params.put("redirect_uri", callback(provider));
        }
        var tokenEndpoint = switch (provider) {
            case NAVER -> "https://nid.naver.com/oauth2.0/token";
            case KAKAO -> "https://kauth.kakao.com/oauth/token";
            case APPLE -> APPLE + "/auth/token";
        };
        var token = read(request(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params))).build());
        return switch (provider) {
            case NAVER -> naver(token);
            case KAKAO -> kakao(token);
            case APPLE -> apple(token, client.id(), state);
        };
    }

    private SocialAccounts.VerifiedIdentity naver(JsonNode token) {
        var response = userInfo("https://openapi.naver.com/v1/nid/me", token);
        if (!"00".equals(response.path("resultcode").asString())) throw invalidResponse();
        // NAVER's profile email has no verification flag. Confirm it through FINSIGHT mail.
        return identity(SocialAccounts.Provider.NAVER, response.path("response").path("id").asString(), null);
    }

    private SocialAccounts.VerifiedIdentity kakao(JsonNode token) {
        var response = userInfo("https://kapi.kakao.com/v2/user/me", token);
        var account = response.path("kakao_account");
        String email = account.path("is_email_valid").asBoolean(false) && account.path("is_email_verified").asBoolean(false)
                ? account.path("email").asString() : null;
        var id = response.path("id");
        if (!id.isIntegralNumber()) throw invalidResponse();
        return identity(SocialAccounts.Provider.KAKAO, id.asString(), email);
    }

    private SocialAccounts.VerifiedIdentity apple(JsonNode token, String clientId, String state) {
        var encoded = token.path("id_token").asString();
        if (encoded == null || encoded.isBlank()) throw invalidResponse();
        try {
            var jwt = appleTokens.decode(encoded);
            if (jwt.getAudience() == null || !jwt.getAudience().contains(clientId) || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(Instant.now())
                    || !AppSessions.hash(state).equals(jwt.getClaimAsString("nonce"))) throw invalidResponse();
            var verified = jwt.getClaims().get("email_verified");
            var email = Boolean.TRUE.equals(verified) || "true".equals(verified) ? jwt.getClaimAsString("email") : null;
            return identity(SocialAccounts.Provider.APPLE, jwt.getSubject(), email);
        } catch (JwtException | IllegalArgumentException invalid) {
            throw invalidResponse();
        }
    }

    private SocialAccounts.VerifiedIdentity identity(SocialAccounts.Provider provider, String subject, String email) {
        if (subject == null || subject.isBlank() || subject.length() > 255) throw invalidResponse();
        return new SocialAccounts.VerifiedIdentity(provider, subject, email);
    }

    private JsonNode userInfo(String endpoint, JsonNode token) {
        var access = token.path("access_token").asString();
        if (access == null || !access.matches("[A-Za-z0-9._~+/=-]{1,4096}")) throw invalidResponse();
        return read(request(endpoint).header("Authorization", "Bearer " + access).GET().build());
    }

    private JsonNode read(HttpRequest request) {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw invalidResponse();
            var data = json.readTree(response.body());
            if (data == null || !data.isObject() || data.hasNonNull("error")) throw invalidResponse();
            return data;
        } catch (IOException | JacksonException invalid) {
            throw invalidResponse();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw invalidResponse();
        }
    }

    private static HttpRequest.Builder request(String endpoint) {
        return HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json");
    }

    private String callback(SocialAccounts.Provider provider) {
        return origin.resolve("/api/v1/auth/social/callback/" + provider.name().toLowerCase(java.util.Locale.ROOT)).toString();
    }

    private static URI checkedOrigin(String value) {
        var uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Social callback origin must be an HTTPS origin");
        }
        return uri;
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "소셜 인증을 확인하지 못했어요.");
    }
}
