package kr.finsight.account;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.HexFormat;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("local")
class PasswordReset {
    interface Mail {
        void deliverReset(String email, String link);
        default void requireAvailable() {}
    }

    private final JdbcClient db;
    private final Mail mail;
    private final URI appOrigin;
    private final URI linkOrigin;
    private final SecureRandom random = new SecureRandom();
    private final Pbkdf2PasswordEncoder passwords = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    PasswordReset(JdbcClient db, Mail mail,
                  @Value("${finsight.signup.web-origin:http://localhost:18081}") URI appOrigin,
                  @Value("${FINSIGHT_PASSWORD_RESET_LINK_ORIGIN:}") String linkOrigin) {
        this.db = db;
        this.mail = mail;
        this.appOrigin = appOrigin;
        this.linkOrigin = linkOrigin.isBlank() ? null : checkedLinkOrigin(linkOrigin);
    }

    @Transactional
    boolean request(String address, String ip) {
        mail.requireAvailable();
        var email = address.strip().toLowerCase(Locale.ROOT);
        var emailKey = hash(email);
        var ipKey = hash(ip);
        lock("reset:ip:" + ipKey);
        lock("reset:email:" + emailKey);
        var now = OffsetDateTime.now();
        var cutoff = now.minusHours(1);
        var byEmail = db.sql("""
                SELECT count(*) AS total, max(requested_at) AS last_request FROM app_password_reset_request
                WHERE email_hash=:email AND requested_at > :cutoff
                """).param("email", emailKey).param("cutoff", cutoff)
                .query((row, index) -> row.getInt("total") >= 5
                        || row.getInt("total") > 0 && row.getObject("last_request", OffsetDateTime.class)
                                .plusSeconds(60).isAfter(now)).single();
        if (byEmail || db.sql("""
                SELECT count(*) FROM app_password_reset_request WHERE ip_hash=:ip AND requested_at > :cutoff
                """).param("ip", ipKey).param("cutoff", cutoff).query(Integer.class).single() >= 20) return false;
        db.sql("INSERT INTO app_password_reset_request VALUES (:email,:ip,:now)")
                .param("email", emailKey).param("ip", ipKey).param("now", now).update();
        var user = db.sql("""
                SELECT id FROM app_user WHERE email=:email AND status IN ('ACTIVE','DATA_DELETION_PENDING')
                    AND password_hash IS NOT NULL FOR UPDATE
                """).param("email", email).query(UUID.class).optional();
        if (user.isEmpty()) return true;
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            mail.deliverReset(email, resetLink(appOrigin, linkOrigin, token));
        } catch (MailDeliveryFailure error) {
            // Keep the rate limit and existing link without exposing whether this address exists.
            return true;
        }
        db.sql("""
                INSERT INTO app_password_reset VALUES (:user,:hash,:expires)
                ON CONFLICT (user_id) DO UPDATE SET token_hash=excluded.token_hash, expires_at=excluded.expires_at
                """).param("user", user.get()).param("hash", hash(token)).param("expires", now.plusMinutes(15)).update();
        return true;
    }

    static URI checkedLinkOrigin(String value) {
        return switch (value) {
            case "https://finsight.life", "finsight-reset://local" -> URI.create(value);
            default -> throw new IllegalArgumentException("비밀번호 재설정 링크 주소를 확인해주세요.");
        };
    }

    static String resetLink(URI web, URI linkOrigin, String token) {
        return (linkOrigin == null ? web.resolve("/#/password-reset?token=" + token)
                : linkOrigin.resolve("/password-reset#token=" + token)).toString();
    }

    @Transactional
    boolean complete(String token, String password) {
        var tokenHash = hash(token);
        // Take the account lock before the token row, in the same order as a resend.
        var user = db.sql("""
                SELECT u.id FROM app_user u JOIN app_password_reset r ON r.user_id=u.id
                WHERE r.token_hash=:hash AND r.expires_at > clock_timestamp() AND u.status IN ('ACTIVE','DATA_DELETION_PENDING')
                FOR UPDATE OF u
                """).param("hash", tokenHash).query(UUID.class).optional();
        if (user.isEmpty()) return false;
        var consumed = db.sql("""
                DELETE FROM app_password_reset WHERE user_id=:user AND token_hash=:hash AND expires_at > clock_timestamp()
                """).param("user", user.get()).param("hash", tokenHash).update();
        if (consumed != 1) return false;
        db.sql("UPDATE app_user SET password_hash=:hash WHERE id=:user")
                .param("hash", passwords.encode(password)).param("user", user.get()).update();
        db.sql("DELETE FROM app_login_failure WHERE email=(SELECT email FROM app_user WHERE id=:user)")
                .param("user", user.get()).update();
        db.sql("DELETE FROM app_session WHERE user_id=:user").param("user", user.get()).update();
        return true;
    }

    private void lock(String key) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))").param("key", key)
                .query((row, index) -> true).single();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
