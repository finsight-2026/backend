package kr.finsight.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import kr.finsight.notification.DiscordOutbox;

@Service
@Profile("local")
class SignupService {
    record Registration(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
                        @NotBlank @Size(min = 8, max = 64)
                        @Pattern(regexp = "(?U)(?=.*[A-Za-z])(?=.*[0-9])[^\\s]+") String password,
                        Map<String, String> profile, boolean terms, boolean privacy, boolean research,
                        String documentVersion) {}
    record Window(OffsetDateTime expiresAt, OffsetDateTime resendAt) {}
    enum Outcome { CREATED, INVALID_CODE, EXPIRED_CODE, DUPLICATE, INVALID_INPUT, POLICY_CHANGED }
    record Challenge(String hash, OffsetDateTime sentAt, int attempts, boolean used) {}

    private final JdbcClient db;
    private final DiscordOutbox discord;
    private final JsonMapper json;
    private final SignupMail mail;
    private final PolicyDocuments policies;
    private final byte[] codeKey;
    private final SecureRandom random = new SecureRandom();
    private final Pbkdf2PasswordEncoder passwords = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    SignupService(JdbcClient db, JsonMapper json, SignupMail mail, DiscordOutbox discord, PolicyDocuments policies,
                  @Value("${finsight.signup.code-key}") String codeKey) {
        this.db = db;
        this.discord = discord;
        this.json = json;
        this.mail = mail;
        this.policies = policies;
        this.codeKey = HexFormat.of().parseHex(codeKey);
        if (this.codeKey.length < 32) throw new IllegalArgumentException("인증 키는 최소 32바이트여야 합니다.");
    }

    @Transactional
    Window send(String address, String ip) {
        var email = normalize(address);
        lock("signup:ip:" + ip);
        lockEmail(email);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var cutoff = now.minusHours(1);
        var last = challenge(email);
        if ((last != null && last.sentAt().plusSeconds(60).isAfter(now))
                || deliveryCount("email", email, cutoff) >= 5
                || deliveryCount("source_ip", ip, cutoff) >= 20) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 요청해주세요.");
        }

        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        while (last != null && sameCode(last.hash(), hash(email, code))) {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        }
        mail.deliver(email, code);
        db.sql("""
                INSERT INTO signup_email_challenge (email, code_hash, sent_at)
                VALUES (:email, :hash, :now)
                ON CONFLICT (email) DO UPDATE SET code_hash = excluded.code_hash,
                    sent_at = excluded.sent_at, attempts = 0, used_at = NULL
                """).param("email", email).param("hash", hash(email, code)).param("now", now).update();
        db.sql("INSERT INTO signup_email_delivery VALUES (:email, :ip, :now)")
                .param("email", email).param("ip", ip).param("now", now).update();
        return new Window(now.plusMinutes(5), now.plusSeconds(60));
    }

    @Transactional
    Outcome register(Registration request) {
        if (!request.terms() || !request.privacy() || request.documentVersion() == null) {
            return Outcome.INVALID_INPUT;
        }
        Map<String, String> profile;
        try {
            profile = ProfileInput.validate(request.profile());
        } catch (IllegalArgumentException invalid) {
            return Outcome.INVALID_INPUT;
        }
        var email = normalize(request.email());
        lockEmail(email);
        if (policies.activeVersion().filter(request.documentVersion()::equals).isEmpty()) return Outcome.POLICY_CHANGED;
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var failure = verifyCode(email, request.code(), now);
        if (failure != null) return failure;
        return create(email, passwords.encode(request.password()), profile, request.research(),
                request.documentVersion(), "EMAIL_SIGNUP", now) == null ? Outcome.DUPLICATE : Outcome.CREATED;
    }

    // A null result means that the active challenge was consumed successfully.
    Outcome verifyCode(String email, String code, OffsetDateTime now) {
        var challenge = challenge(email);
        if (challenge == null || challenge.used() || challenge.attempts() >= 5
                || !challenge.sentAt().plusMinutes(5).isAfter(now)) {
            return Outcome.EXPIRED_CODE;
        }
        if (!sameCode(challenge.hash(), hash(email, code))) {
            db.sql("UPDATE signup_email_challenge SET attempts = attempts + 1 WHERE email = :email")
                    .param("email", email).update();
            return challenge.attempts() == 4 ? Outcome.EXPIRED_CODE : Outcome.INVALID_CODE;
        }
        db.sql("UPDATE signup_email_challenge SET used_at = :now WHERE email = :email")
                .param("email", email).param("now", now).update();
        return null;
    }

    UUID create(String email, String passwordHash, Map<String, String> profile, boolean research,
                String documentVersion, String method, OffsetDateTime now) {
        var id = UUID.randomUUID();
        var inserted = db.sql("""
                INSERT INTO app_user (id, email, password_hash, status)
                VALUES (:id, :email, :password, 'ACTIVE') ON CONFLICT (email) DO NOTHING
                """).param("id", id).param("email", email)
                .param("password", passwordHash, java.sql.Types.VARCHAR).update();
        if (inserted == 0) return null;

        db.sql("INSERT INTO app_user_profile VALUES (:id, CAST(:profile AS jsonb))")
                .param("id", id).param("profile", json.writeValueAsString(profile)).update();
        for (var consent : Map.of("TERMS", true, "PRIVACY", true, "RESEARCH", research).entrySet()) {
            db.sql("INSERT INTO app_user_consent (user_id,document_kind,document_version,granted,recorded_at,method) VALUES (:id, :kind, :version, :granted, :now, :method)")
                    .param("id", id).param("kind", consent.getKey()).param("version", documentVersion).param("method", method)
                    .param("granted", consent.getValue()).param("now", now).update();
        }
        discord.signedUp(id, email);
        return id;
    }

    void lockEmail(String email) {
        lock("signup:email:" + email);
    }

    private void lock(String key) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key).query((row, index) -> true).single();
    }

    private int deliveryCount(String column, String value, OffsetDateTime cutoff) {
        return db.sql("SELECT count(*) FROM signup_email_delivery WHERE " + column + " = :value AND sent_at > :cutoff")
                .param("value", value).param("cutoff", cutoff).query(Integer.class).single();
    }

    private Challenge challenge(String email) {
        return db.sql("SELECT code_hash, sent_at, attempts, used_at IS NOT NULL FROM signup_email_challenge WHERE email = :email")
                .param("email", email).query((row, index) -> new Challenge(row.getString(1),
                        row.getObject(2, OffsetDateTime.class), row.getInt(3), row.getBoolean(4)))
                .optional().orElse(null);
    }

    static String normalize(String address) {
        return address.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean sameCode(String stored, String candidate) {
        return MessageDigest.isEqual(HexFormat.of().parseHex(stored), HexFormat.of().parseHex(candidate));
    }

    private String hash(String email, String code) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(codeKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((email + "\n" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
