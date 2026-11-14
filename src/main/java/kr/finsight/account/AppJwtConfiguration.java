package kr.finsight.account;

import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;

@Configuration
@Profile("local")
class AppJwtConfiguration {
    @Bean
    SecretKey appJwtKey(@Value("${finsight.auth.jwt-key}") String hex) {
        var key = HexFormat.of().parseHex(hex);
        if (key.length < 32) throw new IllegalArgumentException("JWT 키는 최소 32바이트여야 합니다.");
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder appJwtEncoder(SecretKey appJwtKey) {
        return NimbusJwtEncoder.withSecretKey(appJwtKey).build();
    }

    @Bean
    JwtDecoder appJwtDecoder(SecretKey appJwtKey, JdbcClient db) {
        var decoder = NimbusJwtDecoder.withSecretKey(appJwtKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(AppSessions.ISSUER),
                jwt -> validateSession(jwt, db)));
        return decoder;
    }

    private OAuth2TokenValidatorResult validateSession(Jwt jwt, JdbcClient db) {
        try {
            var userId = UUID.fromString(jwt.getSubject());
            var sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
            if (jwt.getExpiresAt() != null && db.sql("""
                    SELECT EXISTS(SELECT 1 FROM app_session s JOIN app_user u ON u.id = s.user_id
                        WHERE s.id = :id AND u.id = :user AND u.status IN ('ACTIVE','DATA_DELETION_PENDING')
                            AND s.expires_at > now())
                    """).param("id", sessionId).param("user", userId).query(Boolean.class).single()) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException | NullPointerException invalidClaim) {
            // Reject tokens without a valid app session identity.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
