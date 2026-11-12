package kr.finsight.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminAuth {
    record Challenge(UUID challengeId, OffsetDateTime expiresAt) {}
    record Login(Challenge challenge, boolean limited) {}
    record Admin(UUID id, String name) {}
    record Identity(Admin admin, List<String> permissions) {}
    record Verified(String token, Identity identity, boolean limited) {}
    private record PasswordRow(UUID id, String hash) {}
    private record MfaRow(UUID adminId, byte[] secret, long lastStep, long version,
                          int failures, OffsetDateTime blockedUntil) {}

    private final JdbcClient db;
    private final AdminTotp totp;
    private final AdminMutationJournal journal;
    private final AdminPasswordPolicy passwordPolicy;
    private final SecureRandom random = new SecureRandom();
    private final Pbkdf2PasswordEncoder passwords = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummy = passwords.encode(UUID.randomUUID().toString());

    AdminAuth(JdbcClient db, AdminTotp totp, AdminMutationJournal journal, AdminPasswordPolicy passwordPolicy) {
        this.db = db;
        this.totp = totp;
        this.journal = journal;
        this.passwordPolicy = passwordPolicy;
    }

    @Transactional
    boolean bootstrap(String email, String password, String seed) {
        lock("admin-bootstrap");
        if (db.sql("SELECT EXISTS(SELECT 1 FROM admin_account)").query(Boolean.class).single()) return false;
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length() > 254
                || password.isBlank() || password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException("초기 관리자 이메일 또는 비밀번호 형식이 잘못되었습니다.");
        }
        var id = UUID.randomUUID();
        passwordPolicy.verifyNew(password);
        db.sql("""
                INSERT INTO admin_account (id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'최고관리자',:hash,:secret,'ACTIVE')
                """).param("id", id).param("email", normalize(email)).param("hash", passwords.encode(password))
                .param("secret", totp.encrypt(AdminTotp.decodeBase32(seed))).update();
        db.sql("INSERT INTO admin_role VALUES (:id, 'SUPER_ADMIN')").param("id", id).update();
        return true;
    }

    @Transactional
    Login login(String address, String password, String ip) {
        var email = normalize(address);
        lock("admin:ip:" + ip);
        lock("admin:email:" + email);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var requests = db.sql("""
                SELECT count(*) FROM admin_login_request WHERE source_ip = :ip AND requested_at > :cutoff
                """).param("ip", ip).param("cutoff", now.minusMinutes(15)).query(Integer.class).single();
        if (requests >= 30) return new Login(null, true);
        db.sql("INSERT INTO admin_login_request VALUES (:ip, :now)").param("ip", ip).param("now", now).update();
        if (db.sql("SELECT EXISTS(SELECT 1 FROM admin_login_failure WHERE email = :email AND blocked_until > :now)")
                .param("email", email).param("now", now).query(Boolean.class).single()) return new Login(null, true);
        var account = db.sql("""
                SELECT id,password_hash FROM admin_account WHERE email = :email AND status = 'ACTIVE' FOR UPDATE
                """).param("email", email)
                .query((row, index) -> new PasswordRow(row.getObject(1, UUID.class), row.getString(2))).optional();
        var matches = passwords.matches(password, account.map(PasswordRow::hash).orElse(dummy));
        if (!matches || account.isEmpty()) {
            var failures = db.sql("""
                    INSERT INTO admin_login_failure VALUES (:email,1,NULL)
                    ON CONFLICT (email) DO UPDATE SET
                        failures = CASE WHEN admin_login_failure.blocked_until IS NULL
                            THEN admin_login_failure.failures + 1 ELSE 1 END,
                        blocked_until = CASE WHEN admin_login_failure.blocked_until IS NULL
                            AND admin_login_failure.failures = 4 THEN :until END
                    RETURNING failures
                    """).param("email", email).param("until", now.plusMinutes(15)).query(Integer.class).single();
            return new Login(null, failures == 5);
        }
        db.sql("DELETE FROM admin_login_failure WHERE email = :email").param("email", email).update();
        var id = account.orElseThrow().id();
        var challenge = new Challenge(UUID.randomUUID(), now.plusMinutes(5));
        db.sql("UPDATE admin_auth_challenge SET used_at = :now WHERE admin_id = :id AND used_at IS NULL")
                .param("id", id).param("now", now).update();
        db.sql("INSERT INTO admin_auth_challenge (id,admin_id,expires_at) VALUES (:challenge,:id,:expiry)")
                .param("challenge", challenge.challengeId()).param("id", id).param("expiry", challenge.expiresAt()).update();
        return new Login(challenge, false);
    }

    @Transactional
    Verified verify(UUID challenge, String code, String recoveryCode) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var id = db.sql("SELECT admin_id FROM admin_auth_challenge WHERE id = :id")
                .param("id", challenge).query(UUID.class).optional();
        if (id.isEmpty()) return new Verified(null, null, false);
        db.sql("SELECT id FROM admin_account WHERE id = :id FOR UPDATE")
                .param("id", id.get()).query(UUID.class).optional();
        var row = db.sql("""
                SELECT a.id,a.totp_secret_encrypted,a.last_totp_step,a.session_version,c.failed_count,a.mfa_blocked_until
                FROM admin_auth_challenge c JOIN admin_account a ON a.id = c.admin_id
                WHERE c.id = :id AND c.used_at IS NULL AND c.expires_at > :now AND a.status = 'ACTIVE'
                FOR UPDATE OF c
                """).param("id", challenge).param("now", now)
                .query((r, index) -> new MfaRow(r.getObject(1, UUID.class), r.getBytes(2), r.getLong(3),
                        r.getLong(4), r.getInt(5), r.getObject(6, OffsetDateTime.class))).optional();
        if (row.isEmpty()) return new Verified(null, null, false);
        var state = row.get();
        if (state.blockedUntil() != null && state.blockedUntil().isAfter(now)) return new Verified(null, null, true);
        long step = code == null ? -1 : totp.matchingStep(state.secret(), code, now.toEpochSecond(), state.lastStep());
        boolean recovered = recoveryCode != null && db.sql("""
                UPDATE admin_recovery_code SET used_at=:now WHERE admin_id=:admin AND code_hash=:hash AND used_at IS NULL
                """).param("now", now).param("admin", state.adminId()).param("hash", hash(recoveryCode)).update() == 1;
        if (step < 0 && !recovered) {
            var limit = state.failures() == 4;
            db.sql("""
                    UPDATE admin_auth_challenge SET failed_count = failed_count + 1,
                      used_at = CASE WHEN failed_count = 4 THEN :now ELSE NULL END WHERE id = :id
                    """).param("id", challenge).param("now", now).update();
            if (limit) db.sql("UPDATE admin_account SET mfa_blocked_until = :until WHERE id = :id")
                    .param("id", state.adminId()).param("until", now.plusMinutes(15)).update();
            return new Verified(null, null, limit);
        }

        db.sql("UPDATE admin_account SET last_totp_step = :step,mfa_reauth_failures=0,mfa_blocked_until=NULL WHERE id = :id")
                .param("step", recovered ? state.lastStep() : step).param("id", state.adminId()).update();
        db.sql("UPDATE admin_auth_challenge SET used_at = :now WHERE id = :id")
                .param("now", now).param("id", challenge).update();
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.sql("INSERT INTO admin_session VALUES (:hash,:id,:now,:now,:reauth,:expiry,:version)")
                .param("hash", hash(token)).param("id", state.adminId()).param("now", now)
                .param("reauth", recovered ? now.minusMinutes(6) : now)
                .param("expiry", now.plusHours(8)).param("version", state.version()).update();
        var identity = identity(token);
        if (recovered) journal.append(identity, "ADMIN_RECOVERY_CODE_USED", "ADMIN_ACCOUNT", state.adminId().toString(), java.util.Map.of());
        return new Verified(token, identity, false);
    }

    Identity identity(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        return db.sql("""
                SELECT a.id,a.name,ARRAY(SELECT r.role FROM admin_role r WHERE r.admin_id = a.id ORDER BY r.role)
                FROM admin_session s JOIN admin_account a ON a.id = s.admin_id
                WHERE s.id_hash = :hash AND a.status = 'ACTIVE' AND s.session_version = a.session_version
                    AND s.expires_at > now() AND s.last_interaction_at > now() - interval '30 minutes'
                """).param("hash", hash(token)).query((r, index) -> new Identity(
                        new Admin(r.getObject(1, UUID.class), r.getString(2)),
                        List.of((String[]) r.getArray(3).getArray()))).optional().orElse(null);
    }

    enum Reauthentication { VERIFIED, INVALID, LIMITED, SESSION_EXPIRED }
    private record ReauthRow(byte[] secret, long step, int failures, OffsetDateTime blockedUntil) {}

    @Transactional
    Reauthentication reauthenticate(String token, String code) {
        var identity = identity(token);
        if (identity == null) return Reauthentication.SESSION_EXPIRED;
        var id = identity.admin().id();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var account = db.sql("""
                SELECT totp_secret_encrypted,last_totp_step,mfa_reauth_failures,mfa_blocked_until
                FROM admin_account WHERE id=:id AND status='ACTIVE' FOR UPDATE
                """).param("id", id).query((row, n) -> new ReauthRow(row.getBytes(1), row.getLong(2),
                row.getInt(3), row.getObject(4, OffsetDateTime.class))).optional();
        if (account.isEmpty() || identity(token) == null) return Reauthentication.SESSION_EXPIRED;
        var state = account.get();
        if (state.blockedUntil() != null && state.blockedUntil().isAfter(now)) return Reauthentication.LIMITED;
        var step = totp.matchingStep(state.secret(), code, now.toEpochSecond(), state.step());
        if (step < 0) {
            boolean limited = state.failures() >= 4;
            db.sql("""
                    UPDATE admin_account SET mfa_reauth_failures=:failures,mfa_blocked_until=:blocked WHERE id=:id
                    """).param("id", id).param("failures", limited ? 0 : state.failures()+1)
                    .param("blocked", limited ? now.plusMinutes(15) : null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
            return limited ? Reauthentication.LIMITED : Reauthentication.INVALID;
        }
        int updated = db.sql("""
                UPDATE admin_session s SET reauth_at=:now,last_interaction_at=:now
                FROM admin_account a WHERE s.id_hash=:hash AND s.admin_id=a.id AND a.id=:admin
                AND s.session_version=a.session_version AND s.expires_at>:now
                AND s.last_interaction_at>:now-interval '30 minutes'
                """).param("hash", hash(token)).param("admin", id).param("now", now).update();
        if (updated == 0) return Reauthentication.SESSION_EXPIRED;
        db.sql("""
                UPDATE admin_account SET last_totp_step=:step,mfa_reauth_failures=0,mfa_blocked_until=NULL WHERE id=:id
                """).param("step", step).param("id", id).update();
        return Reauthentication.VERIFIED;
    }

    boolean recentlyVerified(String token, UUID adminId) {
        return db.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM admin_session s JOIN admin_account a ON a.id = s.admin_id
                    WHERE s.id_hash = :hash AND s.admin_id = :admin AND a.status = 'ACTIVE'
                        AND s.session_version = a.session_version AND s.expires_at > now()
                        AND s.last_interaction_at > now() - interval '30 minutes'
                        AND s.reauth_at > now() - interval '5 minutes'
                )
                """).param("hash", hash(token)).param("admin", adminId).query(Boolean.class).single();
    }

    void lockVerifiedSupervisor(Identity actor, String token) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!actor.permissions().contains("SUPER_ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var recent = db.sql("""
                SELECT s.reauth_at>clock_timestamp()-interval '5 minutes'
                FROM admin_account a JOIN admin_role r ON r.admin_id=a.id AND r.role='SUPER_ADMIN'
                JOIN admin_session s ON s.admin_id=a.id AND s.session_version=a.session_version
                WHERE a.id=:id AND a.status='ACTIVE' AND s.id_hash=:hash AND s.expires_at>clock_timestamp()
                AND s.last_interaction_at>clock_timestamp()-interval '30 minutes' FOR SHARE OF a,r,s
                """).param("id", actor.admin().id()).param("hash", hash(token))
                .query(Boolean.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!recent) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED);
    }

    String passwordHash(String password) {
        passwordPolicy.verifyNew(password);
        return passwords.encode(password);
    }

    boolean passwordMatches(String password, String encoded) {
        return passwords.matches(password, encoded == null ? dummy : encoded);
    }

    boolean recordInteraction(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        return db.sql("""
                UPDATE admin_session s SET last_interaction_at = now()
                FROM admin_account a
                WHERE s.id_hash = :hash AND s.admin_id = a.id AND a.status = 'ACTIVE'
                    AND s.session_version = a.session_version AND s.expires_at > now()
                    AND s.last_interaction_at > now() - interval '30 minutes'
                """).param("hash", hash(token)).update() == 1;
    }

    void logout(String token) {
        if (token != null) db.sql("DELETE FROM admin_session WHERE id_hash = :hash").param("hash", hash(token)).update();
    }

    private void lock(String key) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", key).query((r, index) -> true).single();
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
