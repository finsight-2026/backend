package kr.finsight.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "finsight.auth.jwt-key=" + "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd",
        "finsight.signup.code-key=" + "abababababababababababababababababababababababababababababababab",
        "finsight.admin.mfa-key=" + "efefefefefefefefefefefefefefefefefefefefefefefefefefefefefefefef"
})
class AdminAccountsIntegrationTest {
    private static final String API = "/api/v1/admin";
    private static final String ORIGIN = "https://localhost:18443";
    private static final String SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String PASSWORD = "LocalAdmin-Test-456!";
    private static final String REASON = "운영 계정 등록 및 권한을 검증합니다";

    @TempDir static Path mailbox;
    @Autowired AdminAuth auth;
    @Autowired AdminAccounts accounts;
    @Autowired AdminPasswordReset resets;
    @Autowired MockMvc http;
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    private Cookie csrfCookie;
    private String csrfHeader;
    private String csrfToken;

    @DynamicPropertySource
    static void localMailbox(DynamicPropertyRegistry properties) {
        properties.add("finsight.signup.mailbox", () -> mailbox.toString());
    }

    @Test
    void administratorLifecycleRequiresMfaAndRevokesInvitationsAndSessions() throws Exception {
        var csrf = http.perform(get(API + "/auth/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        var csrfBody = json.readTree(csrf.getResponse().getContentAsString());
        csrfCookie = csrf.getResponse().getCookie("__Secure-FINSIGHT_ADMIN_CSRF");
        csrfHeader = csrfBody.path("headerName").asString();
        csrfToken = csrfBody.path("token").asString();
        assertTrue(auth.bootstrap("root@example.test", PASSWORD, SEED));
        var rootId = db.sql("SELECT id FROM admin_account WHERE email='root@example.test'").query(UUID.class).single();
        var rootChallenge = login("root@example.test");
        var root = body(write("POST", "/auth/mfa", null), Map.of("challengeId", rootChallenge, "code", code(SEED)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(AdminAuthController.SESSION_COOKIE);
        assertNotNull(root);
        http.perform(get(API + "/admins")).andExpect(status().isUnauthorized());
        body(write("PATCH", "/admins/" + rootId, root), Map.of("roles", List.of("OPERATOR"),
                "expectedVersion", 0, "reason", REASON)).andExpect(status().isConflict());
        body(write("PATCH", "/admins/" + rootId, root), Map.of("status", "SUSPENDED",
                "expectedVersion", 0, "reason", REASON)).andExpect(status().isConflict());

        var invite = Map.of("email", "OPERATOR@example.test", "name", "테스트 운영자",
                "roles", List.of("OPERATOR"), "reason", REASON);
        db.sql("UPDATE admin_session SET reauth_at=now()-interval '6 minutes' WHERE admin_id=:id").param("id", rootId).update();
        body(write("POST", "/admins/invitations", root), invite).andExpect(status().isPreconditionRequired());
        db.sql("UPDATE admin_session SET reauth_at=now() WHERE admin_id=:id").param("id", rootId).update();
        var receipt = body(write("POST", "/admins/invitations", root), invite).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.token").doesNotExist());
        var operator = UUID.fromString(value(receipt, "adminId"));
        body(write("POST", "/admins/invitations", root), invite).andExpect(status().isConflict());
        assertEquals("operator@example.test", db.sql("SELECT email FROM admin_account WHERE id=:id")
                .param("id", operator).query(String.class).single());
        var initialToken = mailboxTokens().getFirst();
        assertTrue(db.sql("SELECT EXISTS(SELECT 1 FROM admin_invitation WHERE token_hash=:hash)")
                .param("hash", AdminAuth.hash(initialToken)).query(Boolean.class).single());
        body(write("POST", "/auth/login", null), Map.of("email", "operator@example.test", "password", PASSWORD))
                .andExpect(status().isUnauthorized());

        body(write("POST", "/admins/" + operator + "/resend-invitation", root), Map.of("reason", REASON))
                .andExpect(status().isAccepted());
        var expiredToken = mailboxTokens().stream().filter(token -> !token.equals(initialToken)).findFirst().orElseThrow();
        http.perform(get(API + "/auth/invitations/" + initialToken)).andExpect(status().isNotFound());
        db.sql("UPDATE admin_invitation SET expires_at=now()-interval '1 second' WHERE token_hash=:hash")
                .param("hash", AdminAuth.hash(expiredToken)).update();
        http.perform(get(API + "/auth/invitations/" + expiredToken)).andExpect(status().isNotFound());
        body(write("POST", "/admins/" + operator + "/resend-invitation", root), Map.of("reason", REASON))
                .andExpect(status().isAccepted());
        var token = mailboxTokens().stream().filter(candidate -> !candidate.equals(initialToken) && !candidate.equals(expiredToken))
                .findFirst().orElseThrow();
        http.perform(get(API + "/auth/invitations/" + token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.maskedEmail").value("o***@***"));
        var enrollment = body(write("POST", "/auth/invitations/" + token + "/enrollment", null), Map.of())
                .andExpect(status().isOk());
        var id = value(enrollment, "enrollmentId");
        var seed = value(enrollment, "secret");
        body(write("POST", "/auth/invitations/" + token + "/enrollment", null), Map.of()).andExpect(status().isConflict());
        var form = Map.of("token", token, "enrollmentId", id, "password", PASSWORD, "totpCode", code(seed));
        var activated = body(write("POST", "/auth/activate", null), form).andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10)).andReturn();
        var codes = json.readTree(activated.getResponse().getContentAsString()).path("recoveryCodes");
        body(write("POST", "/auth/activate", null), form).andExpect(status().isNotFound());
        var recovery = codes.get(0).asString();
        var firstSession = recoveryLogin("operator@example.test", recovery);
        http.perform(get(API + "/admins").cookie(firstSession)).andExpect(status().isForbidden());
        assertFalse(auth.recentlyVerified(firstSession.getValue(), operator));
        body(write("POST", "/auth/mfa", null), Map.of("challengeId", login("operator@example.test"), "recoveryCode", recovery))
                .andExpect(status().isUnauthorized());

        body(write("PATCH", "/admins/" + operator, root), Map.of("roles", List.of("OPERATOR", "ANALYST"),
                "expectedVersion", 0, "reason", REASON)).andExpect(status().isConflict());
        body(write("PATCH", "/admins/" + operator, root), Map.of("roles", List.of("OPERATOR", "ANALYST"),
                "expectedVersion", 1, "reason", REASON)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        http.perform(get(API + "/auth/me").cookie(firstSession)).andExpect(status().isUnauthorized());
        var secondSession = recoveryLogin("operator@example.test", codes.get(1).asString());
        body(write("POST", "/admins/" + operator + "/mfa-reset", root), Map.of("reason", REASON))
                .andExpect(status().isAccepted());
        http.perform(get(API + "/auth/me").cookie(secondSession)).andExpect(status().isUnauthorized());
        assertEquals(0L, db.sql("SELECT count(*) FROM admin_recovery_code WHERE admin_id=:id")
                .param("id", operator).query(Long.class).single());
        body(write("POST", "/auth/login", null), Map.of("email", "operator@example.test", "password", PASSWORD))
                .andExpect(status().isUnauthorized());

        var resetToken = mailboxTokens().stream()
                .filter(candidate -> !List.of(initialToken, expiredToken, token).contains(candidate)).findFirst().orElseThrow();
        http.perform(get(API + "/auth/invitations/" + resetToken)).andExpect(jsonPath("$.purpose").value("MFA_RESET"));
        var reset = body(write("POST", "/auth/invitations/" + resetToken + "/enrollment", null), Map.of()).andExpect(status().isOk());
        var resetId = value(reset, "enrollmentId");
        var resetSeed = value(reset, "secret");
        body(write("POST", "/auth/activate", null), Map.of("token", resetToken, "enrollmentId", resetId,
                "password", "WrongLocalPass-456!", "totpCode", code(resetSeed))).andExpect(status().isUnprocessableEntity());
        var reactivated = body(write("POST", "/auth/activate", null), Map.of("token", resetToken, "enrollmentId", resetId,
                "password", PASSWORD, "totpCode", code(resetSeed))).andExpect(status().isOk()).andReturn();
        assertEquals("ACTIVE", db.sql("SELECT status FROM admin_account WHERE id=:id").param("id", operator).query(String.class).single());
        assertEquals(10L, db.sql("SELECT count(*) FROM admin_recovery_code WHERE admin_id=:id AND used_at IS NULL")
                .param("id", operator).query(Long.class).single());
        var newRecovery = json.readTree(reactivated.getResponse().getContentAsString()).path("recoveryCodes").get(0).asString();
        var thirdSession = recoveryLogin("operator@example.test", newRecovery);
        body(write("PATCH", "/admins/" + operator, root), Map.of("status", "SUSPENDED",
                "expectedVersion", 4, "reason", REASON)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
        http.perform(get(API + "/auth/me").cookie(thirdSession)).andExpect(status().isUnauthorized());
        body(write("POST", "/auth/login", null), Map.of("email", "operator@example.test", "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        body(write("PATCH", "/admins/" + operator, root), Map.of("status", "ACTIVE", "roles", List.of("SUPER_ADMIN"),
                "expectedVersion", 5, "reason", REASON)).andExpect(status().isOk());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> accounts.recoverLastSupervisor(operator, REASON));
        body(write("PATCH", "/admins/" + rootId, root), Map.of("roles", List.of("OPERATOR"),
                "expectedVersion", 0, "reason", REASON)).andExpect(status().isOk());
        http.perform(get(API + "/auth/me").cookie(root)).andExpect(status().isUnauthorized());
        var recoveryForReset = json.readTree(reactivated.getResponse().getContentAsString()).path("recoveryCodes").get(1).asString();
        verifyPasswordReset(operator, recoveryForReset);
        verifyServerRecovery(operator);
    }

    private void verifyServerRecovery(UUID admin) throws Exception {
        var password = "LocalAdmin-Reset-789!";
        var oldChallenge = value(body(write("POST", "/auth/login", null),
                Map.of("email", "operator@example.test", "password", password)).andExpect(status().isOk()), "challengeId");
        var recoveryHash = db.sql("SELECT code_hash FROM admin_recovery_code WHERE admin_id=:id AND used_at IS NULL LIMIT 1")
                .param("id", admin).query(String.class).single();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> accounts.recoverLastSupervisor(admin, "짧은 사유"));
        var previous = mailboxTokens();
        assertEquals(admin, accounts.recoverLastSupervisor(admin, REASON).adminId());
        var first = mailboxTokens().stream().filter(token -> !previous.contains(token)).findFirst().orElseThrow();
        http.perform(get(API + "/auth/invitations/" + first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("MFA_RESET"));
        body(write("POST", "/auth/login", null), Map.of("email", "operator@example.test", "password", password))
                .andExpect(status().isUnauthorized());
        assertEquals(0L, db.sql("""
                SELECT count(*) FROM admin_recovery_code WHERE admin_id=:id AND code_hash=:hash
                """).param("id", admin).param("hash", recoveryHash).query(Long.class).single());
        assertEquals(0L, db.sql("""
                SELECT count(*) FROM admin_auth_challenge WHERE id=:challenge
                """).param("challenge", UUID.fromString(oldChallenge)).query(Long.class).single());

        accounts.recoverLastSupervisor(admin, REASON);
        http.perform(get(API + "/auth/invitations/" + first)).andExpect(status().isNotFound());
        var token = mailboxTokens().stream().filter(candidate -> !candidate.equals(first) && !previous.contains(candidate))
                .findFirst().orElseThrow();
        var enrollment = body(write("POST", "/auth/invitations/" + token + "/enrollment", null), Map.of())
                .andExpect(status().isOk());
        var enrollmentId = value(enrollment, "enrollmentId");
        var code = code(value(enrollment, "secret"));
        body(write("POST", "/auth/activate", null), Map.of("token", token, "enrollmentId", enrollmentId,
                "password", PASSWORD, "totpCode", code)).andExpect(status().isUnprocessableEntity());
        body(write("POST", "/auth/activate", null), Map.of("token", token, "enrollmentId", enrollmentId,
                "password", password, "totpCode", code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10));
        assertEquals("ACTIVE", db.sql("SELECT status FROM admin_account WHERE id=:id").param("id", admin).query(String.class).single());
        assertEquals(2L, db.sql("""
                SELECT count(*) FROM audit_log WHERE target_id=:id AND action='ADMIN_SERVER_MFA_RECOVERY'
                    AND changes_safe->>'source'='SERVER_CONSOLE' AND changes_safe->>'reason'=:reason
                    AND cardinality(roles)=0
                """).param("id", admin.toString()).param("reason", REASON).query(Long.class).single());
    }

    private void verifyPasswordReset(UUID admin, String recovery) throws Exception {
        var oldSession = recoveryLogin("operator@example.test", recovery);
        var oldChallenge = login("operator@example.test");
        var resetRequest = "/auth/reset-request";
        var credentials = Map.of("email", "operator@example.test");
        body(write("POST", resetRequest, null), Map.of("email", "unknown@example.test"))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        resets.dispatch();
        assertTrue(resetTokens().isEmpty());
        body(write("POST", resetRequest, null), credentials).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""));
        var firstToken = awaitResetToken(List.of());
        body(write("POST", resetRequest, null), credentials).andExpect(status().isAccepted()).andExpect(content().string(""));
        assertEquals(1, resetTokens().size());
        assertTrue(db.sql("""
                SELECT EXISTS(SELECT 1 FROM admin_password_reset
                    WHERE admin_id=:id AND token_hash=:hash AND delivery_token_encrypted IS NULL)
                """).param("id", admin).param("hash", AdminAuth.hash(firstToken)).query(Boolean.class).single());
        db.sql("UPDATE admin_password_reset SET expires_at=clock_timestamp()-interval '1 second' WHERE admin_id=:id")
                .param("id", admin).update();
        var updatedPassword = "LocalAdmin-Reset-789!";
        body(write("POST", "/auth/reset", null), Map.of("token", firstToken, "newPassword", updatedPassword))
                .andExpect(status().isNotFound());
        db.sql("""
                UPDATE admin_password_reset_request SET requested_at=requested_at-interval '61 seconds' WHERE email_hash=:email
                """).param("email", AdminAuth.hash("operator@example.test")).update();
        body(write("POST", resetRequest, null), credentials).andExpect(status().isAccepted());
        var nextToken = awaitResetToken(List.of(firstToken));
        body(write("POST", "/auth/reset", null), Map.of("token", nextToken, "newPassword", "Password1234"))
                .andExpect(status().isUnprocessableEntity());
        var reset = Map.of("token", nextToken, "newPassword", updatedPassword);
        body(write("POST", "/auth/reset", null), reset).andExpect(status().isNoContent());
        body(write("POST", "/auth/reset", null), reset).andExpect(status().isNotFound());
        http.perform(get(API + "/auth/me").cookie(oldSession)).andExpect(status().isUnauthorized());
        body(write("POST", "/auth/mfa", null), Map.of("challengeId", oldChallenge, "recoveryCode", recovery))
                .andExpect(status().isUnauthorized());
        body(write("POST", "/auth/login", null), Map.of("email", "operator@example.test", "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        body(write("POST", "/auth/login", null), Map.of("email", "operator@example.test", "password", updatedPassword))
                .andExpect(status().isOk()).andExpect(jsonPath("$.challengeId").isString())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(1L, db.sql("SELECT count(*) FROM audit_log WHERE actor_id=:id AND action='ADMIN_PASSWORD_RESET'")
                .param("id", admin).query(Long.class).single());
    }

    private String awaitResetToken(List<String> previous) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            resets.dispatch();
            var token = resetTokens().stream().filter(candidate -> !previous.contains(candidate)).findFirst();
            if (token.isPresent() && db.sql("""
                    SELECT EXISTS(SELECT 1 FROM admin_password_reset WHERE token_hash=:hash AND delivery_token_encrypted IS NULL)
                    """).param("hash", AdminAuth.hash(token.get())).query(Boolean.class).single()) return token.get();
            Thread.sleep(25);
        }
        throw new AssertionError("로컬 관리자 재설정 메일이 생성되지 않았습니다.");
    }

    private List<String> resetTokens() throws Exception {
        try (var files = Files.list(mailbox)) {
            var tokens = new java.util.ArrayList<String>();
            for (var file : files.filter(path -> path.getFileName().toString().startsWith("admin-password-reset-")).toList()) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
                var lines = Files.readAllLines(file);
                assertEquals("operator@example.test", lines.getFirst());
                var fragment = java.net.URI.create(lines.get(1)).getFragment();
                assertTrue(fragment.startsWith("admin-password-reset="));
                tokens.add(fragment.substring("admin-password-reset=".length()));
            }
            return tokens;
        }
    }

    private List<String> mailboxTokens() throws Exception {
        try (var files = Files.list(mailbox)) {
            var tokens = new java.util.ArrayList<String>();
            for (var file : files.filter(path -> path.getFileName().toString().startsWith("admin-invitation-")).toList()) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
                var lines = Files.readAllLines(file);
                assertEquals("operator@example.test", lines.getFirst());
                var fragment = java.net.URI.create(lines.get(1)).getFragment();
                assertTrue(fragment.startsWith("admin-invitation="));
                tokens.add(fragment.substring("admin-invitation=".length()));
            }
            return tokens;
        }
    }

    private String login(String email) throws Exception {
        return value(body(write("POST", "/auth/login", null), Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk()), "challengeId");
    }

    private Cookie recoveryLogin(String email, String recovery) throws Exception {
        return body(write("POST", "/auth/mfa", null), Map.of("challengeId", login(email), "recoveryCode", recovery))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(AdminAuthController.SESSION_COOKIE);
    }

    private MockHttpServletRequestBuilder write(String method, String path, Cookie session) {
        var request = (method.equals("PATCH") ? patch(API + path) : post(API + path))
                .secure(true).header("Origin", ORIGIN).cookie(csrfCookie).header(csrfHeader, csrfToken)
                .contentType("application/json");
        return session == null ? request : request.cookie(session);
    }

    private ResultActions body(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return http.perform(request.content(json.writeValueAsString(body)));
    }

    private String value(ResultActions result, String name) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path(name).asString();
    }

    private static String code(String seed) {
        return AdminTotp.codeAt(AdminTotp.decodeBase32(seed), Instant.now().getEpochSecond()/30);
    }
}
