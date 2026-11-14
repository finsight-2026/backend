package kr.finsight.admin;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminInvitations {
    enum Purpose { INVITATION, MFA_RESET }
    enum Result { ACTIVATED, INVALID, LIMITED }
    record Receipt(UUID adminId, OffsetDateTime expiresAt) {}
    record Preview(String maskedEmail, Purpose purpose, String status, OffsetDateTime expiresAt) {}
    record Enrollment(UUID enrollmentId, String secret, OffsetDateTime expiresAt) {}
    record Activation(Result result, List<String> recoveryCodes) {}
    private record Invitation(UUID adminId, String email, String name, Purpose purpose, OffsetDateTime expiresAt,
                              UUID enrollmentId, byte[] secret, OffsetDateTime enrollmentExpiresAt,
                              OffsetDateTime blockedUntil, int failedCount, String passwordHash) {}

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AdminInvitations.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final AdminTotp totp;
    private final AdminAuth auth;
    private final AdminInvitationMail mail;
    private final AdminMutationJournal journal;
    private final String origin;

    AdminInvitations(JdbcClient db, AdminTotp totp, AdminAuth auth, AdminInvitationMail mail, AdminMutationJournal journal,
                     @Value("${finsight.admin.origin:https://localhost:18443}") String origin) {
        this.db = db;
        this.totp = totp;
        this.auth = auth;
        this.mail = mail;
        this.journal = journal;
        this.origin = origin;
    }

    void lockLifecycle() {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended('admin-account-lifecycle',0))")
                .query((row, n) -> true).single();
    }

    Receipt issue(UUID admin, String email, Purpose purpose) {
        revoke(admin);
        var raw = new byte[32];
        RANDOM.nextBytes(raw);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var expiry = db.sql("""
                INSERT INTO admin_invitation(token_hash,admin_id,purpose,expires_at)
                VALUES (:hash,:admin,:purpose,clock_timestamp()+interval '24 hours') RETURNING expires_at
                """).param("hash", AdminAuth.hash(token)).param("admin", admin).param("purpose", purpose.name())
                .query(OffsetDateTime.class).single();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mail.deliverAdminInvitation(email, origin + "/#admin-invitation=" + token);
                } catch (RuntimeException failure) {
                    LOG.warn("관리자 초대 메일 전달 실패 {}", admin);
                }
            }
        });
        return new Receipt(admin, expiry);
    }

    void revoke(UUID admin) {
        db.sql("""
                UPDATE admin_invitation SET revoked_at=clock_timestamp(),enrollment_secret=NULL,enrollment_id=NULL
                WHERE admin_id=:id AND used_at IS NULL AND revoked_at IS NULL
                """).param("id", admin).update();
    }

    Preview preview(String token) {
        var pending = invitation(hash(token), false);
        return new Preview(AdminUsers.mask(pending.email()), pending.purpose(), "INVITED", pending.expiresAt());
    }

    @Transactional
    Enrollment begin(String token) {
        var hash = hash(token);
        invitation(hash, false);
        lockLifecycle();
        var pending = invitation(hash, true);
        var now = now();
        if (pending.blockedUntil() != null && pending.blockedUntil().isAfter(now)) throw limited();
        if (pending.enrollmentId() != null && pending.enrollmentExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        var secret = new byte[20];
        RANDOM.nextBytes(secret);
        var id = UUID.randomUUID();
        var expires = db.sql("""
                UPDATE admin_invitation SET enrollment_id=:id,enrollment_secret=:secret,
                    enrollment_expires_at=least(expires_at,clock_timestamp()+interval '10 minutes'),
                    failed_count=0,blocked_until=NULL WHERE token_hash=:hash RETURNING enrollment_expires_at
                """).param("id", id).param("secret", totp.encrypt(secret)).param("hash", hash).query(OffsetDateTime.class).single();
        return new Enrollment(id, AdminTotp.encodeBase32(secret), expires);
    }

    @Transactional
    Activation activate(String token, UUID enrollment, String password, String code) {
        var hash = hash(token);
        invitation(hash, false);
        lockLifecycle();
        var pending = invitation(hash, true);
        var now = now();
        if (pending.blockedUntil() != null && pending.blockedUntil().isAfter(now)) return result(Result.LIMITED);
        if (!enrollment.equals(pending.enrollmentId()) || !pending.enrollmentExpiresAt().isAfter(now)) throw missing();
        var step = totp.matchingStep(pending.secret(), code, now.toEpochSecond(), -1);
        if (step < 0 || (pending.purpose() == Purpose.MFA_RESET && !auth.passwordMatches(password, pending.passwordHash()))) {
            boolean limited = pending.failedCount() >= 4;
            db.sql("""
                    UPDATE admin_invitation SET failed_count=failed_count+1,
                        blocked_until=CASE WHEN :limited THEN clock_timestamp()+interval '15 minutes' END,
                        enrollment_secret=CASE WHEN :limited THEN NULL ELSE enrollment_secret END,
                        enrollment_id=CASE WHEN :limited THEN NULL ELSE enrollment_id END
                    WHERE token_hash=:hash
                    """).param("hash", hash).param("limited", limited).update();
            return result(limited ? Result.LIMITED : Result.INVALID);
        }

        var admin = pending.adminId();
        db.sql("""
                UPDATE admin_account SET status='ACTIVE',password_hash=:password,totp_secret_encrypted=:secret,
                    last_totp_step=:step,session_version=session_version+1,version=version+1,
                    mfa_blocked_until=NULL,mfa_reauth_failures=0 WHERE id=:id
                """).param("id", admin).param("secret", pending.secret()).param("step", step)
                .param("password", pending.purpose() == Purpose.MFA_RESET ? pending.passwordHash() : auth.passwordHash(password)).update();
        db.sql("""
                UPDATE admin_invitation SET used_at=clock_timestamp(),enrollment_secret=NULL,enrollment_id=NULL WHERE token_hash=:hash
                """).param("hash", hash).update();
        db.sql("DELETE FROM admin_recovery_code WHERE admin_id=:id").param("id", admin).update();
        db.sql("DELETE FROM admin_login_failure WHERE email=:email").param("email", pending.email()).update();
        var codes = new ArrayList<String>(10);
        for (int i = 0; i < 10; i++) {
            var bytes = new byte[10];
            RANDOM.nextBytes(bytes);
            var recovery = AdminTotp.encodeBase32(bytes);
            db.sql("INSERT INTO admin_recovery_code(admin_id,code_hash) VALUES (:id,:hash)")
                    .param("id", admin).param("hash", AdminAuth.hash(recovery)).update();
            codes.add(recovery);
        }
        var roles = db.sql("SELECT role FROM admin_role WHERE admin_id=:id ORDER BY role").param("id", admin).query(String.class).list();
        var actor = new AdminAuth.Identity(new AdminAuth.Admin(admin, pending.name()), roles);
        journal.append(actor, pending.purpose() == Purpose.MFA_RESET ? "ADMIN_MFA_REENROLLED" : "ADMIN_INVITATION_ACCEPTED",
                "ADMIN_ACCOUNT", admin.toString(), Map.of());
        return new Activation(Result.ACTIVATED, List.copyOf(codes));
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void expire() {
        db.sql("""
                UPDATE admin_invitation SET enrollment_secret=NULL,enrollment_id=NULL
                WHERE enrollment_secret IS NOT NULL AND enrollment_expires_at<=clock_timestamp()
                """).update();
        db.sql("DELETE FROM admin_invitation WHERE expires_at<clock_timestamp()-interval '30 days'").update();
    }

    private Invitation invitation(String hash, boolean lock) {
        var sql = """
                SELECT a.id AS admin_id,a.email,a.name,i.purpose,i.expires_at,i.enrollment_id,
                    i.enrollment_secret AS secret,i.enrollment_expires_at,i.blocked_until,i.failed_count,a.password_hash
                FROM admin_invitation i JOIN admin_account a ON a.id=i.admin_id
                WHERE i.token_hash=:hash AND a.status='INVITED' AND i.used_at IS NULL AND i.revoked_at IS NULL
                    AND i.expires_at>clock_timestamp()
                """;
        return db.sql(sql + (lock ? " FOR UPDATE OF a,i" : ""))
                .param("hash", hash).query(Invitation.class).optional().orElseThrow(AdminInvitations::missing);
    }

    private OffsetDateTime now() {
        return db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
    }

    private static String hash(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw missing();
        return AdminAuth.hash(raw);
    }

    private static Activation result(Result result) {
        return new Activation(result, List.of());
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static ResponseStatusException limited() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    }
}
