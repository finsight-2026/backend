package kr.finsight.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("local")
class AppSessions {
    static final String ISSUER = "finsight-app";
    record Tokens(String accessToken, String refreshToken, int expiresIn, String revocationToken, long contentEventEpoch) {}
    record Login(Tokens tokens, boolean limited, OffsetDateTime withdrawalDueAt) {}
    record Authentication(UUID userId, boolean limited) {}
    private record Account(UUID id, String hash) {}
    private record AccountStatus(String status, OffsetDateTime withdrawalDueAt) {}
    private record Session(UUID id, UUID userId, long contentEventEpoch) {}

    private final JdbcClient db;
    private final JwtEncoder encoder;
    private final SecureRandom random = new SecureRandom();
    private final Pbkdf2PasswordEncoder passwords = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String unknownPasswordHash = passwords.encode(UUID.randomUUID().toString());

    AppSessions(JdbcClient db, JwtEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    @Transactional
    Login login(String address, String password, String ip) {
        var identity = authenticate(address, password, ip, "LOGIN");
        if (identity.userId() == null) return new Login(null, identity.limited(), null);
        return issueForAuthenticated(identity.userId());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Login issueForAuthenticated(UUID user) {
        var account = db.sql("""
                SELECT status, withdrawal_due_at FROM app_user WHERE id=:user FOR UPDATE
                """).param("user", user).query(AccountStatus.class).optional().orElse(null);
        if (account == null) return new Login(null, false, null);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if ("WITHDRAWAL_PENDING".equals(account.status())) {
            var due = account.withdrawalDueAt();
            return new Login(null, false, due != null && due.isAfter(now) ? due : null);
        }
        if (!"ACTIVE".equals(account.status()) && !"DATA_DELETION_PENDING".equals(account.status())) {
            return new Login(null, false, null);
        }
        var epoch = db.sql("UPDATE app_user SET last_login_at=:now WHERE id=:user RETURNING content_event_epoch")
                .param("now", now).param("user", user).query(Long.class).single();
        var session = new Session(UUID.randomUUID(), user, epoch);
        var refresh = newToken();
        var revocation = newToken();
        db.sql("INSERT INTO app_session (id,user_id,refresh_hash,expires_at,revocation_hash) VALUES (:id, :user, :hash, :expiry, :revocation)")
                .param("id", session.id()).param("user", session.userId()).param("hash", hash(refresh))
                .param("expiry", now.plusDays(30)).param("revocation", hash(revocation)).update();
        return new Login(tokens(session, refresh, revocation), false, null);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Authentication authenticate(String address, String password, String ip, String status) {
        var email = address.strip().toLowerCase(Locale.ROOT);
        lock("app-login:ip:" + ip);
        lock("app-login:email:" + email);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (db.sql("""
                SELECT count(*) FROM app_login_request WHERE source_ip = :ip AND requested_at > :cutoff
                """).param("ip", ip).param("cutoff", now.minusMinutes(15)).query(Integer.class).single() >= 30) {
            return new Authentication(null, true);
        }
        db.sql("INSERT INTO app_login_request VALUES (:ip, :now)").param("ip", ip).param("now", now).update();
        if (db.sql("SELECT EXISTS(SELECT 1 FROM app_login_failure WHERE email = :email AND blocked_until > :now)")
                .param("email", email).param("now", now).query(Boolean.class).single()) {
            return new Authentication(null, true);
        }
        var account = db.sql("""
                SELECT id, password_hash FROM app_user
                WHERE email = :email AND password_hash IS NOT NULL
                    AND (status = :status OR (:status = 'LOGIN' AND
                        (status IN ('ACTIVE','DATA_DELETION_PENDING')
                            OR (status = 'WITHDRAWAL_PENDING' AND withdrawal_due_at > clock_timestamp()))))
                FOR UPDATE
                """).param("email", email).param("status", status)
                .query((row, index) -> new Account(row.getObject(1, UUID.class), row.getString(2))).optional();
        var matches = passwords.matches(password, account.map(Account::hash).orElse(unknownPasswordHash));
        if (!matches || account.isEmpty()) {
            var failures = db.sql("""
                    INSERT INTO app_login_failure VALUES (:email, 1, NULL)
                    ON CONFLICT (email) DO UPDATE SET
                        failures = CASE WHEN app_login_failure.blocked_until IS NULL
                            THEN app_login_failure.failures + 1 ELSE 1 END,
                        blocked_until = CASE WHEN app_login_failure.blocked_until IS NULL
                            AND app_login_failure.failures = 4 THEN :until END
                    RETURNING failures
                    """).param("email", email).param("until", now.plusMinutes(15)).query(Integer.class).single();
            return new Authentication(null, failures == 5);
        }
        db.sql("DELETE FROM app_login_failure WHERE email = :email").param("email", email).update();
        return new Authentication(account.orElseThrow().id(), false);
    }

    @Transactional
    Tokens refresh(String oldToken) {
        var refresh = newToken();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        return db.sql("""
                UPDATE app_session AS s SET refresh_hash = :newHash, expires_at = :expiry
                FROM app_user AS u WHERE u.id = s.user_id AND u.status IN ('ACTIVE','DATA_DELETION_PENDING')
                AND s.refresh_hash = :oldHash AND s.expires_at > :now
                RETURNING s.id, s.user_id, u.content_event_epoch
                """).param("newHash", hash(refresh)).param("oldHash", hash(oldToken))
                .param("now", now).param("expiry", now.plusDays(30))
                .query((row, index) -> new Session(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getLong(3)))
                .optional().map(session -> tokens(session, refresh, null)).orElse(null);
    }

    void logout(UUID sessionId, UUID userId) {
        db.sql("DELETE FROM app_session WHERE id = :id AND user_id = :user")
                .param("id", sessionId).param("user", userId).update();
    }

    void revoke(String revocationToken) {
        db.sql("DELETE FROM app_session WHERE revocation_hash = :hash")
                .param("hash", hash(revocationToken)).update();
    }

    private Tokens tokens(Session session, String refresh, String revocation) {
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject(session.userId().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(900)).claim("sid", session.id().toString()).build();
        var token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new Tokens(token.getTokenValue(), refresh, 900, revocation, session.contentEventEpoch());
    }

    String newToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private void lock(String key) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key).query((row, index) -> true).single();
    }
}
