package kr.finsight.admin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("local")
class AdminPasswordReset {
    private record Account(UUID id, String name, String email) {}
    private record Delivery(UUID id, String email, byte[] encrypted) {}

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AdminPasswordReset.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;
    private final AdminAuth auth;
    private final AdminTotp encryption;
    private final AdminInvitationMail mail;
    private final AdminMutationJournal journal;
    private final TransactionTemplate transactions;
    private final String origin;

    AdminPasswordReset(JdbcClient db, AdminAuth auth, AdminTotp encryption, AdminInvitationMail mail,
                       AdminMutationJournal journal, TransactionTemplate transactions,
                       @Value("${finsight.admin.origin:https://localhost:18443}") String origin) {
        this.db = db;
        this.auth = auth;
        this.encryption = encryption;
        this.mail = mail;
        this.journal = journal;
        this.transactions = transactions;
        this.origin = origin;
    }

    @Transactional
    void request(String address, String remoteAddress) {
        var email = AdminAuth.normalize(address);
        var emailHash = AdminAuth.hash(email);
        var ipHash = AdminAuth.hash(remoteAddress);
        lock("admin-reset:ip:" + ipHash);
        lock("admin-reset:email:" + emailHash);
        var now = db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        var cutoff = now.minusHours(1);
        boolean emailLimited = db.sql("""
                SELECT count(*)>=5 OR coalesce(max(requested_at)>:recent,false)
                FROM admin_password_reset_request WHERE email_hash=:email AND requested_at>:cutoff
                """).param("email", emailHash).param("recent", now.minusMinutes(1)).param("cutoff", cutoff).query(Boolean.class).single();
        boolean ipLimited = db.sql("""
                SELECT count(*)>=20 FROM admin_password_reset_request WHERE ip_hash=:ip AND requested_at>:cutoff
                """).param("ip", ipHash).param("cutoff", cutoff).query(Boolean.class).single();
        if (emailLimited || ipLimited) return;
        db.sql("INSERT INTO admin_password_reset_request VALUES (:email,:ip,:now)")
                .param("email", emailHash).param("ip", ipHash).param("now", now).update();
        var account = db.sql("""
                SELECT id FROM admin_account WHERE email=:email AND status='ACTIVE' FOR UPDATE
                """).param("email", email).query(UUID.class).optional();
        if (account.isEmpty()) return;
        var random = new byte[32];
        RANDOM.nextBytes(random);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        db.sql("""
                INSERT INTO admin_password_reset(admin_id,token_hash,expires_at,delivery_token_encrypted)
                VALUES (:id,:hash,:expires,:encrypted)
                ON CONFLICT(admin_id) DO UPDATE SET token_hash=excluded.token_hash,expires_at=excluded.expires_at,
                    delivery_token_encrypted=excluded.delivery_token_encrypted,next_delivery_at=clock_timestamp()
                """).param("id", account.get()).param("hash", AdminAuth.hash(token)).param("expires", now.plusMinutes(15))
                .param("encrypted", encryption.encrypt(token.getBytes(StandardCharsets.US_ASCII))).update();
    }

    @Transactional
    boolean complete(String token, String password) {
        var hash = AdminAuth.hash(token);
        var account = db.sql("""
                SELECT a.id,a.name,a.email FROM admin_password_reset r JOIN admin_account a ON a.id=r.admin_id
                WHERE r.token_hash=:hash AND a.status='ACTIVE' AND r.expires_at>clock_timestamp() FOR UPDATE OF a
                """).param("hash", hash).query(Account.class).optional();
        if (account.isEmpty()) return false;
        var admin = account.get();
        int consumed = db.sql("""
                DELETE FROM admin_password_reset WHERE admin_id=:id AND token_hash=:hash AND expires_at>clock_timestamp()
                """).param("id", admin.id()).param("hash", hash).update();
        if (consumed == 0) return false;
        db.sql("""
                UPDATE admin_account SET password_hash=:hash,session_version=session_version+1,version=version+1 WHERE id=:id
                """).param("id", admin.id()).param("hash", auth.passwordHash(password)).update();
        db.sql("DELETE FROM admin_session WHERE admin_id=:id").param("id", admin.id()).update();
        db.sql("DELETE FROM admin_auth_challenge WHERE admin_id=:id").param("id", admin.id()).update();
        db.sql("DELETE FROM admin_login_failure WHERE email=:email").param("email", admin.email()).update();
        List<String> roles = db.sql("SELECT role FROM admin_role WHERE admin_id=:id ORDER BY role")
                .param("id", admin.id()).query(String.class).list();
        var actor = new AdminAuth.Identity(new AdminAuth.Admin(admin.id(), admin.name()), roles);
        journal.append(actor, "ADMIN_PASSWORD_RESET", "ADMIN_ACCOUNT", admin.id().toString(), Map.of());
        return true;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    void dispatch() {
        transactions.executeWithoutResult(status -> {
            var next = db.sql("""
                    SELECT r.admin_id AS id,a.email,r.delivery_token_encrypted AS encrypted
                    FROM admin_password_reset r JOIN admin_account a ON a.id=r.admin_id
                    WHERE a.status='ACTIVE' AND r.delivery_token_encrypted IS NOT NULL
                    AND r.expires_at>clock_timestamp() AND r.next_delivery_at<=clock_timestamp()
                    ORDER BY r.next_delivery_at,r.admin_id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                    """).query(Delivery.class).optional();
            if (next.isEmpty()) return;
            var delivery = next.get();
            try {
                var token = new String(encryption.decrypt(delivery.encrypted()), StandardCharsets.US_ASCII);
                mail.deliverAdminPasswordReset(delivery.email(), origin + "/#admin-password-reset=" + token);
            } catch (RuntimeException failure) {
                LOG.warn("로컬 관리자 재설정 메일 기록 실패 {}", delivery.id());
                db.sql("UPDATE admin_password_reset SET next_delivery_at=clock_timestamp()+interval '30 seconds' WHERE admin_id=:id")
                        .param("id", delivery.id()).update();
                return;
            }
            db.sql("UPDATE admin_password_reset SET delivery_token_encrypted=NULL WHERE admin_id=:id")
                    .param("id", delivery.id()).update();
        });
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void expire() {
        db.sql("DELETE FROM admin_password_reset WHERE expires_at<=clock_timestamp()").update();
        db.sql("DELETE FROM admin_password_reset_request WHERE requested_at<=clock_timestamp()-interval '1 hour'").update();
    }

    private void lock(String key) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))").param("key", key)
                .query((row, n) -> true).single();
    }
}
