package kr.finsight.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kr.finsight.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuthenticationExpiryJobTest {
    @Autowired JdbcClient db;

    @Test
    @Transactional
    void removesExpiredAuthenticationRecordsAndTheirDependentsButKeepsCurrentWindows() {
        var now = db.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        var expiredUser = account();
        var activeUser = account();
        var expiredApp = appCredentials(expiredUser, now.minusMinutes(1));
        var activeApp = appCredentials(activeUser, now.plusMinutes(1));
        var admin = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'검증','fixture',decode('00','hex'),'ACTIVE')
                """).param("id", admin).param("email", admin + "@example.test").update();
        var expiredAdmin = adminSession(admin, now.minusMinutes(2), now.minusMinutes(1));
        var inactiveAdmin = adminSession(admin, now.minusMinutes(31), now.plusHours(1));
        var activeAdmin = adminSession(admin, now.minusMinutes(29), now.plusHours(1));
        for (var session : List.of(expiredAdmin, inactiveAdmin, activeAdmin)) {
            db.sql("""
                    INSERT INTO pii_grant(id,admin_id,session_hash,user_id,scopes,reason_encrypted,expires_at)
                    VALUES (gen_random_uuid(),:admin,:session,:user,ARRAY['PROFILE'],decode('00','hex'),:expiry)
                    """).param("admin", admin).param("session", session).param("user", activeUser)
                    .param("expiry", now.plusMinutes(5)).update();
        }
        for (var expiry : List.of(now.minusMinutes(1), now.plusMinutes(1))) {
            db.sql("INSERT INTO admin_auth_challenge(id,admin_id,expires_at) VALUES (gen_random_uuid(),:admin,:expiry)")
                    .param("admin", admin).param("expiry", expiry).update();
        }
        var marker = UUID.randomUUID().toString();
        recordRequests(marker, now.minusMinutes(61), now.minusMinutes(16));
        recordRequests(marker, now.minusMinutes(59), now.minusMinutes(14));

        var job = new AuthenticationExpiryJob(db);
        job.expire();
        job.expire();

        assertEquals(List.of(activeApp), db.sql("SELECT id FROM app_session WHERE id IN (:ids)")
                .param("ids", List.of(expiredApp, activeApp)).query(UUID.class).list());
        assertEquals(List.of(activeApp), db.sql("SELECT session_id FROM app_push_registration WHERE session_id IN (:ids)")
                .param("ids", List.of(expiredApp, activeApp)).query(UUID.class).list());
        assertEquals(List.of(activeUser), db.sql("SELECT user_id FROM app_password_reset WHERE user_id IN (:ids)")
                .param("ids", List.of(expiredUser, activeUser)).query(UUID.class).list());
        assertEquals(List.of(activeAdmin), db.sql("SELECT id_hash FROM admin_session WHERE admin_id=:admin")
                .param("admin", admin).query(String.class).list());
        assertEquals(List.of(activeAdmin), db.sql("SELECT session_hash FROM pii_grant WHERE admin_id=:admin")
                .param("admin", admin).query(String.class).list());
        assertEquals(1, db.sql("SELECT count(*) FROM admin_auth_challenge WHERE admin_id=:admin")
                .param("admin", admin).query(Integer.class).single());
        assertEquals(1, count("signup_email_challenge", "code_hash", marker));
        assertEquals(1, count("signup_email_delivery", "source_ip", marker));
        assertEquals(1, count("app_password_reset_request", "ip_hash", marker));
        assertEquals(1, count("app_login_request", "source_ip", marker));
        assertEquals(1, count("admin_login_request", "source_ip", marker));
    }

    private UUID account() {
        var user = UUID.randomUUID();
        db.sql("INSERT INTO app_user(id,email,status) VALUES (:id,:email,'ACTIVE')")
                .param("id", user).param("email", user + "@example.test").update();
        return user;
    }

    private UUID appCredentials(UUID user, OffsetDateTime expiry) {
        var session = UUID.randomUUID();
        db.sql("INSERT INTO app_session(id,user_id,refresh_hash,expires_at) VALUES (:id,:user,:token,:expiry)")
                .param("id", session).param("user", user).param("token", session.toString()).param("expiry", expiry).update();
        db.sql("INSERT INTO app_password_reset(user_id,token_hash,expires_at) VALUES (:user,:token,:expiry)")
                .param("user", user).param("token", user.toString()).param("expiry", expiry).update();
        db.sql("INSERT INTO app_push_registration(session_id,provider,registration_token) VALUES (:id,'LOCAL',:token)")
                .param("id", session).param("token", session.toString()).update();
        return session;
    }

    private String adminSession(UUID admin, OffsetDateTime interaction, OffsetDateTime expiry) {
        var session = UUID.randomUUID().toString();
        db.sql("INSERT INTO admin_session VALUES (:hash,:id,:interaction,:interaction,:interaction,:expiry,0)")
                .param("hash", session).param("id", admin).param("interaction", interaction).param("expiry", expiry).update();
        return session;
    }

    private void recordRequests(String marker, OffsetDateTime emailAt, OffsetDateTime loginAt) {
        var email = UUID.randomUUID() + "@example.test";
        db.sql("INSERT INTO signup_email_challenge(email,code_hash,sent_at) VALUES (:email,:marker,:at)")
                .param("email", email).param("marker", marker).param("at", emailAt).update();
        db.sql("INSERT INTO signup_email_delivery VALUES (:email,:marker,:at)")
                .param("email", email).param("marker", marker).param("at", emailAt).update();
        db.sql("INSERT INTO app_password_reset_request VALUES (:email,:marker,:at)")
                .param("email", email).param("marker", marker).param("at", emailAt).update();
        for (var table : List.of("app_login_request", "admin_login_request")) {
            db.sql("INSERT INTO " + table + " VALUES (:marker,:at)")
                    .param("marker", marker).param("at", loginAt).update();
        }
    }

    private int count(String table, String column, String marker) {
        return db.sql("SELECT count(*) FROM " + table + " WHERE " + column + "=:marker")
                .param("marker", marker).query(Integer.class).single();
    }
}
