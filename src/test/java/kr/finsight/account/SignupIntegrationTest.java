package kr.finsight.account;

import static org.junit.jupiter.api.Assertions.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.UUID;
import kr.finsight.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.server.ResponseStatusException;

@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({TestcontainersConfiguration.class, SignupIntegrationTest.MailConfiguration.class})
@SpringBootTest(properties = {"finsight.auth.jwt-key=cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd",
        "finsight.signup.code-key=abababababababababababababababababababababababababababababababab",
        "FINSIGHT_IOS_APP_ID=TEST123456.kr.finsight.finsight", "finsight.deletion.worker-enabled=false"})
class SignupIntegrationTest {
    @Autowired SignupService signup;
    @Autowired AppSessions appSessions;
    @Autowired SocialAccounts socialAccounts;
    @org.springframework.test.context.bean.override.mockito.MockitoBean SocialProviderGateway socialProvider;
    @Autowired kr.finsight.news.NewsEditorial news;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    kr.finsight.notification.DiscordOutbox discord;
    @Autowired WithdrawalService withdrawals;
    @Autowired WithdrawalJob withdrawalJob;
    @Autowired DataDeletionService dataDeletion;
    @Autowired AppDataExports exports;
    @Autowired kr.finsight.jobs.JobLeases exportLeases;
    @Autowired kr.finsight.media.MediaStore media;
    @Autowired CapturedMail mail;
    @Autowired JdbcClient db;
    @Autowired MockMvc http;
    @Autowired JsonMapper json;

    @Test
    void socialCallbackRequiresProviderStateAndAppVerifierAndCannotBeReplayed() throws Exception {
        var provider = SocialAccounts.Provider.APPLE;
        var email = UUID.randomUUID() + "@example.test";
        org.mockito.Mockito.when(socialProvider.configured(provider)).thenReturn(true);
        org.mockito.Mockito.when(socialProvider.authorizationUrl(org.mockito.ArgumentMatchers.eq(provider),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(java.net.URI.create("https://appleid.apple.com/auth/authorize"));
        var start = json.readTree(http.perform(post("/api/v1/auth/social/start").contentType("application/json")
                        .content("{\"provider\":\"APPLE\",\"purpose\":\"LOGIN\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
        var state = start.path("state").asString();
        var verifier = start.path("verifier").asString();
        org.mockito.Mockito.when(socialProvider.exchange(provider, state, "sample-code"))
                .thenReturn(new SocialAccounts.VerifiedIdentity(provider, UUID.randomUUID().toString(), email));
        var callback = "/api/v1/auth/social/callback/apple";
        http.perform(post(callback).contentType("application/x-www-form-urlencoded")
                        .param("state", "x".repeat(43)).param("code", "sample-code"))
                .andExpect(status().isNotFound());
        http.perform(post(callback).contentType("application/x-www-form-urlencoded")
                        .param("state", state).param("code", "sample-code"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "finsight-social://auth/complete?state=" + state));
        http.perform(post(callback).contentType("application/x-www-form-urlencoded")
                        .param("state", state).param("code", "sample-code"))
                .andExpect(status().isNotFound());
        var finish = "/api/v1/auth/social/finish";
        http.perform(post(finish).contentType("application/json").content(json.writeValueAsString(
                Map.of("state", state, "verifier", "x".repeat(43))))).andExpect(status().isNotFound());
        var proof = json.writeValueAsString(Map.of("state", state, "verifier", verifier));
        http.perform(post("/api/v1/account/social/finish").contentType("application/json").content(proof))
                .andExpect(status().isUnauthorized());
        http.perform(post(finish).contentType("application/json").content(proof))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ONBOARDING"))
                .andExpect(jsonPath("$.verifiedEmail").value(email)).andExpect(jsonPath("$.onboardingToken").isString());
        http.perform(post(finish).contentType("application/json").content(proof)).andExpect(status().isNotFound());
    }

    @Test
    void verifiedSocialSignupKeepsConsentAndUsesTheSameSessionLifecycle() throws Exception {
        var email = UUID.randomUUID() + "@example.test";
        var identity = new SocialAccounts.VerifiedIdentity(SocialAccounts.Provider.APPLE, UUID.randomUUID().toString(), email);
        var started = socialAccounts.signIn(identity);
        assertNull(started.login());
        assertEquals(email, started.verifiedEmail());
        var input = registration(email, "000000");
        var rejected = socialAccounts.register(new SocialAccounts.Registration(started.onboardingToken(),
                "someone@example.test", null, input.profile(), true, true, false, input.documentVersion()));
        assertEquals(SocialAccounts.Result.INVALID_INPUT, rejected.result());
        var request = new SocialAccounts.Registration(started.onboardingToken(), email, null,
                input.profile(), true, true, false, input.documentVersion());
        var registered = socialAccounts.register(request);
        assertEquals(SocialAccounts.Result.CREATED, registered.result());
        assertEquals(SocialAccounts.Result.EXPIRED, socialAccounts.register(request).result());
        var bearer = "Bearer " + registered.tokens().accessToken();
        http.perform(get("/api/v1/account/settings").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.passwordEnabled").value(false)).andExpect(jsonPath("$.socialProviders[0]").value("APPLE"));
        http.perform(get("/api/v1/account/settings/consents").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].method").value("SOCIAL_SIGNUP"));

        var user = accountId(email);
        var session = sessionId(registered.tokens().accessToken());
        var other = new SocialAccounts.VerifiedIdentity(SocialAccounts.Provider.APPLE, UUID.randomUUID().toString(), email);
        assertFalse(socialAccounts.reauthenticate(user, session, other));
        assertTrue(socialAccounts.reauthenticate(user, session, identity));
        assertEquals(SocialAccounts.Result.LAST_CREDENTIAL,
                socialAccounts.unlink(user, session, SocialAccounts.Provider.APPLE));
        assertNotNull(withdrawals.request(user, session));
        assertNotNull(socialAccounts.signIn(identity).login().withdrawalDueAt());
        assertNull(appSessions.refresh(registered.tokens().refreshToken()));
        assertFalse(socialAccounts.cancelWithdrawal(other));
        assertTrue(socialAccounts.cancelWithdrawal(identity));
        assertFalse(socialAccounts.cancelWithdrawal(identity));
        assertNotNull(socialAccounts.signIn(identity).login().tokens());
    }

    @Test
    void socialReauthenticationResultIsBoundToTheInitiatingAccountSession() throws Exception {
        var owner = createAuthorization("127.0.0.84");
        var other = createAuthorization("127.0.0.85");
        var provider = SocialAccounts.Provider.KAKAO;
        var subject = UUID.randomUUID().toString();
        var ownerId = UUID.fromString(json.readTree(java.util.Base64.getUrlDecoder().decode(
                owner.substring(7).split("\\.")[1])).path("sub").asString());
        db.sql("INSERT INTO app_social_identity(provider,provider_subject,user_id) VALUES ('KAKAO',:subject,:user)")
                .param("subject", subject).param("user", ownerId).update();
        org.mockito.Mockito.when(socialProvider.configured(provider)).thenReturn(true);
        org.mockito.Mockito.when(socialProvider.authorizationUrl(org.mockito.ArgumentMatchers.eq(provider),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(java.net.URI.create("https://kauth.kakao.com/oauth/authorize"));
        var start = json.readTree(http.perform(post("/api/v1/account/social/start").header("Authorization", owner)
                        .contentType("application/json").content("{\"provider\":\"KAKAO\",\"purpose\":\"REAUTH\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var state = start.path("state").asString();
        var proof = json.writeValueAsString(Map.of("state", state, "verifier", start.path("verifier").asString()));
        org.mockito.Mockito.when(socialProvider.exchange(provider, state, "sample-code"))
                .thenReturn(new SocialAccounts.VerifiedIdentity(provider, subject, null));
        http.perform(get("/api/v1/auth/social/callback/kakao").param("state", state).param("code", "sample-code"))
                .andExpect(status().isSeeOther());
        var finish = "/api/v1/account/social/finish";
        http.perform(post(finish).header("Authorization", other).contentType("application/json").content(proof))
                .andExpect(status().isNotFound());
        http.perform(post("/api/v1/auth/social/finish").contentType("application/json").content(proof))
                .andExpect(status().isNotFound());
        http.perform(post(finish).header("Authorization", owner).contentType("application/json").content(proof))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REAUTHENTICATED"));
        assertEquals("ACTIVE", db.sql("SELECT status FROM app_user WHERE id=:user")
                .param("user", ownerId).query(String.class).single());
    }

    @Test
    void matchingSocialEmailCannotLinkUntilTheExistingAccountReauthenticates() {
        var email = UUID.randomUUID() + "@example.test";
        signup.send(email, "127.0.0.82");
        var input = registration(email, mail.code);
        assertEquals(SignupService.Outcome.CREATED, signup.register(input));
        var identity = new SocialAccounts.VerifiedIdentity(SocialAccounts.Provider.KAKAO, UUID.randomUUID().toString(), email);
        var started = socialAccounts.signIn(identity);
        assertNull(started.login());
        var user = accountId(email);
        var login = appSessions.login(email, input.password(), "127.0.0.82").tokens();
        var session = sessionId(login.accessToken());
        assertEquals(SocialAccounts.Result.REAUTH_REQUIRED,
                socialAccounts.linkSameEmail(user, session, started.onboardingToken()));
        assertEquals(SocialAccounts.Result.EXISTING_ACCOUNT,
                socialAccounts.register(new SocialAccounts.Registration(started.onboardingToken(), email, null,
                        input.profile(), true, true, false, input.documentVersion())).result());
        assertEquals(user, withdrawals.reauthenticate(user, session, input.password(), "127.0.0.82").userId());
        db.sql("UPDATE app_session SET reauthenticated_at=clock_timestamp()-interval '5 minutes' WHERE id=:id")
                .param("id", session).update();
        assertEquals(SocialAccounts.Result.REAUTH_REQUIRED,
                socialAccounts.linkSameEmail(user, session, started.onboardingToken()));
        assertEquals(user, withdrawals.reauthenticate(user, session, input.password(), "127.0.0.82").userId());
        assertEquals(SocialAccounts.Result.LINKED, socialAccounts.linkSameEmail(user, session, started.onboardingToken()));
        assertEquals(SocialAccounts.Result.EXPIRED, socialAccounts.linkSameEmail(user, session, started.onboardingToken()));
        assertNotNull(socialAccounts.signIn(identity).login().tokens());
    }

    @Test
    void socialSignupWithoutAnAssertedEmailRequiresAnEmailChallenge() {
        var email = UUID.randomUUID() + "@example.test";
        var identity = new SocialAccounts.VerifiedIdentity(SocialAccounts.Provider.NAVER, UUID.randomUUID().toString(), null);
        var started = socialAccounts.signIn(identity);
        assertNull(started.verifiedEmail());
        var input = registration(email, "000000");
        assertEquals(SocialAccounts.Result.EXPIRED_CODE,
                socialAccounts.register(new SocialAccounts.Registration(started.onboardingToken(), email, "000000",
                        input.profile(), true, true, false, input.documentVersion())).result());
        signup.send(email, "127.0.0.83");
        assertEquals(SocialAccounts.Result.CREATED,
                socialAccounts.register(new SocialAccounts.Registration(started.onboardingToken(), email, mail.code,
                        input.profile(), true, true, false, input.documentVersion())).result());
    }

    private UUID accountId(String email) {
        return db.sql("SELECT id FROM app_user WHERE email=:email").param("email", email).query(UUID.class).single();
    }

    private UUID sessionId(String accessToken) {
        return UUID.fromString(json.readTree(java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]))
                .path("sid").asString());
    }

    @Test
    void passwordResetAssociationAndLinksUseOnlyTheApprovedAppAndPath() throws Exception {
        http.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.applinks.details[0].appIDs[0]").value("TEST123456.kr.finsight.finsight"))
                .andExpect(jsonPath("$.applinks.details[0].components[0]['/']").value("/password-reset"));
        var web = java.net.URI.create("http://localhost:18081");
        var token = "a".repeat(43);
        assertEquals("https://finsight.life/password-reset#token=" + token,
                PasswordReset.resetLink(web, PasswordReset.checkedLinkOrigin("https://finsight.life"), token));
        assertEquals("finsight-reset://local/password-reset#token=" + token,
                PasswordReset.resetLink(web, PasswordReset.checkedLinkOrigin("finsight-reset://local"), token));
        assertEquals("http://localhost:18081/#/password-reset?token=" + token,
                PasswordReset.resetLink(web, null, token));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordReset.checkedLinkOrigin("https://finsight.life@invalid.example"));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void widgetUsesConsecutiveKoreanCalendarMonthsAndHidesSensitiveFieldsByDefault() throws Exception {
        var path = "/api/v1/diagnosis/attempts/widget";
        http.perform(get(path)).andExpect(status().isUnauthorized());
        var owner = createAuthorization("127.0.0.68");
        var other = createAuthorization("127.0.0.69");
        var seoul = java.time.ZoneId.of("Asia/Seoul");
        var today = java.time.LocalDate.now(seoul);
        var last = today.withDayOfMonth(1).minusMonths(1).plusDays(3);
        for (var day : java.util.List.of(last, last.minusDays(1), last.minusMonths(1), last.minusMonths(3))) {
            var started = http.perform(post("/api/v1/diagnosis/attempts").header("Authorization", owner))
                    .andExpect(status().isCreated()).andReturn().getResponse();
            var id = json.readTree(started.getContentAsString()).path("id").asString();
            http.perform(post("/api/v1/diagnosis/attempts/" + id + "/submit").header("Authorization", owner)
                            .contentType("application/json").content(json.writeValueAsString(
                                    Map.of("answers", java.util.Collections.nCopies(30, 3)))))
                    .andExpect(status().isOk());
            db.sql("UPDATE diagnosis SET completed_at=:completed WHERE attempt_id=:attempt")
                    .param("completed", day.atTime(0, 5).atZone(seoul).toOffsetDateTime())
                    .param("attempt", UUID.fromString(id)).update();
        }
        http.perform(get(path).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.streakMonths").value(2))
                .andExpect(jsonPath("$.lastDiagnosisDate").value(last.toString()))
                .andExpect(jsonPath("$.nextDiagnosisDate").value(last.plusDays(30).toString()))
                .andExpect(jsonPath("$.daysUntilNext").value((int) java.time.temporal.ChronoUnit.DAYS.between(today, last.plusDays(30))))
                .andExpect(jsonPath("$.score").doesNotExist()).andExpect(jsonPath("$.representativeType").doesNotExist());
        http.perform(get(path).param("showScore", "true").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(50)).andExpect(jsonPath("$.representativeType").doesNotExist())
                .andExpect(jsonPath("$.maximumPressureScore").doesNotExist());
        http.perform(get(path).param("showType", "true").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.score").doesNotExist()).andExpect(jsonPath("$.representativeType").value("DEBT"))
                .andExpect(jsonPath("$.maximumPressureScore").doesNotExist());
        http.perform(get(path).param("showScore", "true").param("showType", "true").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maximumPressureScore").value(50));
        http.perform(get(path).param("showScore", "true").param("showType", "true").header("Authorization", other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.streakMonths").value(0))
                .andExpect(jsonPath("$.lastDiagnosisDate").doesNotExist());

        var actionPath = "/api/v1/guides/widget";
        http.perform(get(actionPath)).andExpect(status().isUnauthorized());
        http.perform(get(actionPath).header("Authorization", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NO_DIAGNOSIS")).andExpect(jsonPath("$.title").doesNotExist());
        var action = json.readTree(http.perform(get(actionPath).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("AVAILABLE")).andReturn().getResponse().getContentAsString());
        var guideId = action.path("id").asString();
        var guideAttempt = action.path("attemptId").asString();
        var completion = "/api/v1/guides/" + guideId + "/completion";
        http.perform(put(completion).param("attemptId", guideAttempt).header("Authorization", owner)
                        .contentType("application/json").content("{\"completed\":true}"))
                .andExpect(status().isOk());
        var next = json.readTree(http.perform(get(actionPath).header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertNotEquals(guideId, next.path("id").asString());
        http.perform(put(completion).param("attemptId", guideAttempt).header("Authorization", owner)
                        .contentType("application/json").content("{\"completed\":false}"))
                .andExpect(status().isOk());
        http.perform(get(actionPath).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(guideId));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void policyReleasesPreserveHistoryAndRequireCurrentVersionForNewConsent() throws Exception {
        var base = "/api/v1/policies";
        var origin = "http://localhost:18081";
        http.perform(get(base).header("Origin", origin)).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.activeVersion").value(PolicyDocuments.PREVIEW_VERSION));
        var production = new PolicyDocuments(db, false);
        assertTrue(production.catalog().documents().isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> production.detail("terms", PolicyDocuments.PREVIEW_VERSION)).getStatusCode().value());
        var insert = """
                INSERT INTO policy_release(version,effective_date,terms,privacy_policy,privacy_consent,research)
                VALUES (:version,(statement_timestamp() AT TIME ZONE 'Asia/Seoul')::date+:days,
                        '이용약관 ' || :version,'개인정보 처리방침','수집·이용 동의','연구 안내')
                """;
        db.sql(insert).param("version", "release-v1").param("days", -2).update();
        db.sql(insert).param("version", "release-v2").param("days", -1).update();
        db.sql(insert).param("version", "release-v4").param("days", 1).update();
        http.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.activeVersion").value("release-v2"));
        http.perform(get(base + "/terms/release-v1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("HISTORICAL"))
                .andExpect(jsonPath("$.body").value("이용약관 release-v1"));
        http.perform(get(base + "/privacy-policy/release-v4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.document.status").value("SCHEDULED"));
        http.perform(get(base + "/unrecognized/release-v2")).andExpect(status().isNotFound());

        var email = UUID.randomUUID() + "@example.test";
        var ip = "127.0.0.67";
        signup.send(email, ip);
        var data = json.valueToTree(registration(email, mail.code));
        var body = (tools.jackson.databind.node.ObjectNode) data;
        var verify = "/api/v1/auth/signup/verify";
        for (var stale : java.util.List.of(PolicyDocuments.PREVIEW_VERSION, "release-v1", "release-v4")) {
            body.put("documentVersion", stale);
            http.perform(post(verify).contentType("application/json").content(json.writeValueAsString(body)))
                    .andExpect(status().isPreconditionFailed());
        }
        body.put("documentVersion", "release-v2").put("research", true);
        http.perform(post(verify).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
        var auth = "Bearer " + appSessions.login(email, "Finsight123", ip).tokens().accessToken();
        var consents = "/api/v1/account/settings/consents";
        http.perform(get(consents).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].version").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("release-v2"))));

        db.sql(insert).param("version", "release-v3").param("days", 0).update();
        http.perform(put(consents + "/research").header("Authorization", auth).contentType("application/json")
                        .content("{\"granted\":true,\"version\":\"release-v2\"}"))
                .andExpect(status().isPreconditionFailed());
        for (int i = 0; i < 2; i++) {
            http.perform(put(consents + "/research").header("Authorization", auth).contentType("application/json")
                            .content("{\"granted\":false}"))
                    .andExpect(status().isNoContent());
        }
        http.perform(get(consents).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$[0].version").value("release-v2"))
                .andExpect(jsonPath("$[0].granted").value(false));
        http.perform(put(consents + "/research").header("Authorization", auth).contentType("application/json")
                        .content("{\"granted\":true,\"version\":\"release-v3\"}"))
                .andExpect(status().isNoContent());
        http.perform(get(consents).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("release-v3")).andExpect(jsonPath("$[0].granted").value(true));
    }

    @Test
    void browserCanRequestAndVerifyCodeFromTheConfiguredOrigin() throws Exception {
        var url = "/api/v1/auth/signup";
        var origin = "http://localhost:18081";
        var email = UUID.randomUUID() + "@example.test";
        http.perform(options(url + "/code").header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
        http.perform(options(url + "/code").header("Origin", "http://localhost:18082")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        http.perform(post(url + "/code").header("Origin", origin).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.resendAt").isString());
        http.perform(post(url + "/verify").header("Origin", origin).contentType("application/json")
                        .content(json.writeValueAsString(registration(email, mail.code))))
                .andExpect(status().isCreated());
    }

    @Test
    void resendInvalidatesOldCodeAndSuccessfulRegistrationIsAtomic() {
        var email = UUID.randomUUID() + "@example.test";
        signup.send(email, "127.0.0.2");
        var previousCode = mail.code;
        var limited = assertThrows(ResponseStatusException.class, () -> signup.send(email, "127.0.0.2"));
        assertEquals(429, limited.getStatusCode().value());
        db.sql("UPDATE signup_email_challenge SET sent_at = sent_at - interval '61 seconds' WHERE email = :email")
                .param("email", email).update();
        signup.send(email, "127.0.0.2");
        assertNotEquals(previousCode, mail.code);

        assertEquals(SignupService.Outcome.INVALID_CODE, signup.register(registration(email, previousCode)));
        assertEquals(SignupService.Outcome.CREATED, signup.register(registration(email, mail.code)));
        assertEquals(SignupService.Outcome.EXPIRED_CODE, signup.register(registration(email, mail.code)));

        var user = db.sql("SELECT id FROM app_user WHERE email = :email")
                .param("email", email).query(UUID.class).single();
        org.mockito.Mockito.verify(discord).signedUp(user, email);
        assertEquals(1, db.sql("""
                SELECT count(*) FROM app_user u JOIN app_user_profile p ON p.user_id = u.id
                WHERE u.email = :email AND u.password_hash <> 'Finsight123'
                """).param("email", email).query(Integer.class).single());
        assertEquals(3, db.sql("""
                SELECT count(*) FROM app_user u JOIN app_user_consent c ON c.user_id = u.id
                WHERE u.email = :email AND c.granted = (c.document_kind <> 'RESEARCH')
                """).param("email", email).query(Integer.class).single());
    }

    @Test
    void fifthFailureInvalidatesEvenTheCorrectCode() {
        var email = UUID.randomUUID() + "@example.test";
        signup.send(email, "127.0.0.3");
        var invalid = mail.code.equals("000000") ? "111111" : "000000";
        for (int attempt = 0; attempt < 4; attempt++) {
            assertEquals(SignupService.Outcome.INVALID_CODE, signup.register(registration(email, invalid)));
        }
        assertEquals(SignupService.Outcome.EXPIRED_CODE, signup.register(registration(email, invalid)));
        assertEquals(SignupService.Outcome.EXPIRED_CODE, signup.register(registration(email, mail.code)));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void queuedLogoutIsIdempotentAndRevokesOnlyItsOwnSessionAfterRotation() throws Exception {
        var email = UUID.randomUUID() + "@example.test";
        signup.send(email, "127.0.0.70");
        assertEquals(SignupService.Outcome.CREATED, signup.register(registration(email, mail.code)));
        var credentials = json.writeValueAsString(Map.of("email", email, "password", "Finsight123"));
        var login = http.perform(post("/api/v1/auth/login").contentType("application/json").content(credentials))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        var first = json.readTree(login.getContentAsString());
        var proof = first.path("revocationToken").asString();
        var other = appSessions.login(email, "Finsight123", "127.0.0.70").tokens();
        assertNotEquals(first.path("refreshToken").asString(), proof);

        var refresh = json.writeValueAsString(Map.of("refreshToken", first.path("refreshToken").asString()));
        var rotatedResponse = http.perform(post("/api/v1/auth/refresh").contentType("application/json").content(refresh))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        var rotated = json.readTree(rotatedResponse.getContentAsString());
        var path = "/api/v1/diagnosis/attempts/widget";
        var revokedBearer = "Bearer " + rotated.path("accessToken").asString();
        http.perform(get(path).header("Authorization", revokedBearer)).andExpect(status().isOk());

        var logout = json.writeValueAsString(Map.of("revocationToken", proof));
        for (int attempt = 0; attempt < 2; attempt++) {
            http.perform(post("/api/v1/auth/logout/revoke").contentType("application/json").content(logout))
                    .andExpect(status().isNoContent());
        }
        http.perform(get(path).header("Authorization", revokedBearer)).andExpect(status().isUnauthorized());
        http.perform(get(path).header("Authorization", "Bearer " + other.accessToken())).andExpect(status().isOk());
        http.perform(post("/api/v1/auth/refresh").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("refreshToken", rotated.path("refreshToken").asString()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void pushRegistrationRotatesAndBelongsOnlyToAnActiveSession() throws Exception {
        var path = "/api/v1/notifications/device";
        var token = "local-" + UUID.randomUUID();
        var bytes = new byte[3072];
        new java.security.SecureRandom().nextBytes(bytes);
        var rotated = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var secondToken = "local-" + UUID.randomUUID();
        var registration = json.writeValueAsString(Map.of("provider", "LOCAL", "token", token));
        var replacement = json.writeValueAsString(Map.of("provider", "LOCAL", "token", rotated));
        http.perform(put(path).contentType("application/json").content(registration))
                .andExpect(status().isUnauthorized());
        var email = UUID.randomUUID() + "@example.test";
        signup.send(email, "127.0.0.71");
        assertEquals(SignupService.Outcome.CREATED, signup.register(registration(email, mail.code)));
        var first = appSessions.login(email, "Finsight123", "127.0.0.71").tokens();
        var second = appSessions.login(email, "Finsight123", "127.0.0.71").tokens();
        var firstBearer = "Bearer " + first.accessToken();
        var secondBearer = "Bearer " + second.accessToken();
        http.perform(put(path).header("Authorization", firstBearer).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("provider", "LOCAL", "token", "invalid"))))
                .andExpect(status().isBadRequest());
        http.perform(put(path).header("Authorization", firstBearer).contentType("application/json").content(registration))
                .andExpect(status().isConflict());
        http.perform(put("/api/v1/notifications/preferences").header("Authorization", firstBearer)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("setting", "ENABLED", "enabled", true))))
                .andExpect(status().isOk());

        for (var body : java.util.List.of(registration, registration, replacement)) {
            http.perform(put(path).header("Authorization", firstBearer).contentType("application/json").content(body))
                    .andExpect(status().isNoContent());
        }
        var registered = """
                SELECT registration_token FROM app_push_registration
                WHERE registration_token IN (:old,:rotated,:second)
                ORDER BY registration_token
                """;
        java.util.function.Supplier<java.util.List<String>> deviceTokens = () -> db.sql(registered)
                .param("old", token).param("rotated", rotated).param("second", secondToken)
                .query(String.class).list();
        assertEquals(java.util.List.of(rotated), deviceTokens.get());

        var secondBody = json.writeValueAsString(Map.of("provider", "LOCAL", "token", secondToken));
        http.perform(put(path).header("Authorization", secondBearer).contentType("application/json").content(secondBody))
                .andExpect(status().isNoContent());
        var revoke = json.writeValueAsString(Map.of("revocationToken", first.revocationToken()));
        http.perform(post("/api/v1/auth/logout/revoke").contentType("application/json").content(revoke))
                .andExpect(status().isNoContent());
        assertEquals(java.util.List.of(secondToken), deviceTokens.get());
        http.perform(put(path).header("Authorization", firstBearer).contentType("application/json").content(replacement))
                .andExpect(status().isUnauthorized());

        var replacementSession = appSessions.login(email, "Finsight123", "127.0.0.71").tokens();
        var replacementBearer = "Bearer " + replacementSession.accessToken();
        http.perform(put(path).header("Authorization", replacementBearer).contentType("application/json").content(secondBody))
                .andExpect(status().isNoContent());
        http.perform(delete(path)
                        .header("Authorization", secondBearer)).andExpect(status().isNoContent());
        assertEquals(java.util.List.of(secondToken), deviceTokens.get());
        http.perform(delete(path)
                        .header("Authorization", replacementBearer)).andExpect(status().isNoContent());
        assertTrue(deviceTokens.get().isEmpty());
    }

    @Test
    void verifiedAccountCanRotateRefreshAndLogoutRevokesTheSession() throws Exception {
        var email = UUID.randomUUID() + "@example.test";
        signup.send(email, "127.0.0.4");
        assertEquals(SignupService.Outcome.CREATED, signup.register(registration(email, mail.code)));
        var login = http.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", "Finsight123"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresIn").value(900)).andReturn();
        var issued = json.readTree(login.getResponse().getContentAsString());
        var oldRefresh = issued.get("refreshToken").asString();
        var refreshRequest = json.writeValueAsString(Map.of("refreshToken", oldRefresh));
        var response = http.perform(post("/api/v1/auth/refresh").contentType("application/json").content(refreshRequest))
                .andExpect(status().isOk()).andReturn();
        var rotated = json.readTree(response.getResponse().getContentAsString());
        assertNotEquals(oldRefresh, rotated.get("refreshToken").asString());
        http.perform(post("/api/v1/auth/refresh").contentType("application/json").content(refreshRequest))
                .andExpect(status().isUnauthorized());
        var authorization = "Bearer " + rotated.get("accessToken").asString();
        http.perform(post("/api/v1/auth/logout").header("Authorization", authorization))
                .andExpect(status().isNoContent());
        http.perform(post("/api/v1/auth/logout").header("Authorization", authorization))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/auth/refresh").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("refreshToken", rotated.get("refreshToken").asString()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownAccountAndKnownAccountHaveSameFailureAndLockout() {
        var email = UUID.randomUUID() + "@example.test";
        var unknown = UUID.randomUUID() + "@example.test";
        signup.send(email, "127.0.0.5");
        assertEquals(SignupService.Outcome.CREATED, signup.register(registration(email, mail.code)));
        for (int index = 0; index < 5; index++) {
            var knownResult = appSessions.login(email, "Incorrect123", "127.0.0.5");
            var unknownResult = appSessions.login(unknown, "Incorrect123", "127.0.0.5");
            assertEquals(knownResult, unknownResult);
            assertNull(knownResult.tokens());
            assertEquals(index == 4, knownResult.limited());
        }
        assertTrue(appSessions.login(email, "Finsight123", "127.0.0.5").limited());
    }

    @Test
    void diagnosisHttpRoutesUseTheAuthenticatedOwnerAndStoreOneResult() throws Exception {
        var url = "/api/v1/diagnosis/attempts";
        http.perform(post(url)).andExpect(status().isUnauthorized());
        var owner = createAuthorization("127.0.0.6");
        var other = createAuthorization("127.0.0.7");
        var started = http.perform(post(url).header("Authorization", owner))
                .andExpect(status().isCreated()).andReturn();
        var attempt = json.readTree(started.getResponse().getContentAsString()).get("id").asString();
        var path = url + "/" + attempt;
        var answers = json.writeValueAsString(Map.of("answers", java.util.Collections.nCopies(30, 3)));
        http.perform(post(path + "/submit").header("Authorization", other)
                        .contentType("application/json").content(answers))
                .andExpect(status().isNotFound());
        var result = http.perform(post(path + "/submit").header("Authorization", owner)
                        .contentType("application/json").content(answers))
                .andExpect(status().isOk()).andReturn();
        var id = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        http.perform(post(path + "/submit").header("Authorization", owner)
                        .contentType("application/json").content(answers))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.result.total.numerator").value(1300))
                .andExpect(jsonPath("$.result.total.denominator").value(26))
                .andExpect(jsonPath("$.result.rankedPressures.length()").value(6))
                .andExpect(jsonPath("$.result.resources.length()").value(3));
        http.perform(get(path + "/result").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        http.perform(get(path + "/result").header("Authorization", other))
                .andExpect(status().isNotFound());
    }

    @Test
    void historyPagesOnlyCompletedOwnedAttemptsUsingStableCursor() throws Exception {
        var url = "/api/v1/diagnosis/attempts";
        var owner = createAuthorization("127.0.0.8");
        var other = createAuthorization("127.0.0.9");
        var started = http.perform(post(url).header("Authorization", owner))
                .andExpect(status().isCreated()).andReturn();
        var attempt = UUID.fromString(json.readTree(started.getResponse().getContentAsString()).get("id").asString());
        var answers = json.writeValueAsString(Map.of("answers", java.util.Collections.nCopies(30, 3)));
        http.perform(post(url + "/" + attempt + "/submit").header("Authorization", owner)
                        .contentType("application/json").content(answers))
                .andExpect(status().isOk());
        http.perform(post(url).header("Authorization", owner)).andExpect(status().isCreated());
        db.sql("""
                WITH copies AS (
                    INSERT INTO survey_attempt (id, user_id, survey_version, formula_version, started_at)
                    SELECT gen_random_uuid(), user_id, survey_version, formula_version, started_at
                    FROM survey_attempt CROSS JOIN generate_series(1, 21)
                    WHERE id = :id RETURNING id
                )
                INSERT INTO diagnosis (id, attempt_id, completed_at, representative_type, score_snapshot)
                SELECT gen_random_uuid(), copies.id, d.completed_at, d.representative_type, d.score_snapshot
                FROM copies CROSS JOIN diagnosis d WHERE d.attempt_id = :id
                """).param("id", attempt).update();
        var first = http.perform(get(url).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].score").value(50))
                .andExpect(jsonPath("$.hasMore").value(true)).andReturn();
        var cursor = json.readTree(first.getResponse().getContentAsString()).get("items").get(19);
        var next = http.perform(get(url).header("Authorization", owner)
                        .param("before", cursor.get("completedAt").asString()).param("beforeId", cursor.get("id").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(false)).andReturn();
        var latest = json.readTree(first.getResponse().getContentAsString()).get("items").get(0);
        http.perform(get(url + "/trend").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(latest.get("id").asString()));
        http.perform(get(url + "/trend").header("Authorization", other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertFalse(next.getResponse().getContentAsString().contains(cursor.get("id").asString()));
        http.perform(get(url).header("Authorization", other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        http.perform(get(url).header("Authorization", owner).param("before", cursor.get("completedAt").asString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withdrawalRequiresRecentReauthenticationAndFinalizesOnlyAfterGracePeriod() throws Exception {
        var email = UUID.randomUUID() + "@example.test";
        signup.send(email, "127.0.0.10");
        assertEquals(SignupService.Outcome.CREATED, signup.register(registration(email, mail.code)));
        var first = appSessions.login(email, "Finsight123", "127.0.0.10").tokens();
        var second = appSessions.login(email, "Finsight123", "127.0.0.10").tokens();
        var path = "/api/v1/account";
        var bearer = "Bearer " + first.accessToken();
        var credentials = json.writeValueAsString(Map.of("password", "Finsight123"));
        http.perform(post(path + "/withdrawal").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        http.perform(post(path + "/reauth").header("Authorization", bearer)
                        .contentType("application/json").content(credentials))
                .andExpect(status().isNoContent());
        http.perform(post(path + "/withdrawal").header("Authorization", bearer))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.dueAt").isString());
        var canceledRequest = db.sql("SELECT withdrawal_request_id FROM app_user WHERE email = :email")
                .param("email", email).query(UUID.class).single();
        assertTrue(db.sql("""
                SELECT EXISTS(SELECT 1 FROM app_event e JOIN deletion_operation o ON o.id=e.event_id
                    WHERE o.id=:id AND e.event_name='USER_WITHDRAWAL_REQUESTED'
                      AND e.occurred_at=o.requested_at AND e.received_at=o.requested_at)
                """).param("id", canceledRequest).query(Boolean.class).single());
        org.mockito.Mockito.verify(discord).withdrawalRequested(canceledRequest, email);
        http.perform(get("/api/v1/diagnosis/attempts").header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isUnauthorized());
        assertNull(appSessions.refresh(first.refreshToken()));
        assertNull(appSessions.refresh(second.refreshToken()));
        var login = json.writeValueAsString(Map.of("email", email, "password", "Finsight123"));
        http.perform(post("/api/v1/auth/login").contentType("application/json").content(login))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WITHDRAWAL_PENDING"))
                .andExpect(jsonPath("$.dueAt").isString()).andExpect(jsonPath("$.accessToken").doesNotExist());
        assertTrue(withdrawals.duePurges().isEmpty());

        var cancel = json.writeValueAsString(Map.of("email", email, "password", "Finsight123", "confirmed", true));
        http.perform(post(path + "/withdrawal/cancel").contentType("application/json").content(cancel))
                .andExpect(status().isNoContent());
        assertEquals("CANCELED", db.sql("SELECT status FROM deletion_operation WHERE id=:id")
                .param("id", canceledRequest).query(String.class).single());
        bearer = "Bearer " + appSessions.login(email, "Finsight123", "127.0.0.10").tokens().accessToken();
        http.perform(post(path + "/reauth").header("Authorization", bearer)
                        .contentType("application/json").content(credentials))
                .andExpect(status().isNoContent());
        http.perform(post(path + "/withdrawal").header("Authorization", bearer))
                .andExpect(status().isAccepted());
        var completedRequest = db.sql("SELECT withdrawal_request_id FROM app_user WHERE email = :email")
                .param("email", email).query(UUID.class).single();
        org.mockito.Mockito.verify(discord).withdrawalRequested(completedRequest, email);
        db.sql("UPDATE app_user SET withdrawal_due_at = now() - interval '1 second' WHERE email = :email")
                .param("email", email).update();
        http.perform(post(path + "/withdrawal/cancel").contentType("application/json").content(cancel))
                .andExpect(status().isUnauthorized());
        var purge = new WithdrawalService.Purge(completedRequest, 0, 0);
        assertTrue(withdrawals.finalizePurge(purge));
        assertFalse(withdrawals.finalizePurge(purge));
        assertEquals(0, db.sql("SELECT count(*) FROM app_event WHERE event_id IN (:requests)")
                .param("requests", java.util.List.of(canceledRequest, completedRequest)).query(Integer.class).single());
        org.mockito.Mockito.verify(discord).withdrawalCompleted(completedRequest, email, 1L);
        org.mockito.Mockito.verify(discord, org.mockito.Mockito.never()).withdrawalCompleted(
                org.mockito.ArgumentMatchers.eq(canceledRequest), org.mockito.ArgumentMatchers.eq(email),
                org.mockito.ArgumentMatchers.anyLong());
        assertEquals(1L, db.sql("SELECT completed_count FROM withdrawal_total").query(Long.class).single());
        assertEquals(0, db.sql("SELECT count(*) FROM app_user WHERE email = :email")
                .param("email", email).query(Integer.class).single());
        assertEquals("COMPLETED", db.sql("""
                SELECT status FROM deletion_operation WHERE id=:id AND started_at IS NOT NULL
                AND completed_at>=started_at AND due_at=requested_at+interval '360 hours'
                """).param("id", completedRequest).query(String.class).single());
    }

    @Test
    void withdrawalPurgeRollsBackAndRetriesWithoutBlockingOtherAccounts() {
        var bad = UUID.randomUUID();
        var healthy = UUID.randomUUID();
        var badEmail = bad + "@example.test";
        var healthyEmail = healthy + "@example.test";
        long before = db.sql("SELECT completed_count FROM withdrawal_total").query(Long.class).single();
        for (var request : java.util.List.of(bad, healthy)) {
            db.sql("""
                    INSERT INTO app_user(id,email,status,withdrawal_request_id,withdrawal_due_at)
                    VALUES (gen_random_uuid(),:email,'WITHDRAWAL_PENDING',:id,
                        now()-CASE WHEN :id=:bad THEN interval '2 minutes' ELSE interval '1 minute' END)
                    """).param("email", request + "@example.test").param("id", request).param("bad", bad).update();
            db.sql("""
                    INSERT INTO deletion_operation(id,kind,status,requested_at,due_at)
                    VALUES (:id,'ACCOUNT_WITHDRAWAL','SCHEDULED',now()-interval '337 hours',now()+interval '23 hours')
                    """).param("id", request).update();
        }
        db.sql("""
                CREATE FUNCTION test_fail_withdrawal_completion() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'injected failure after account delete';
                END;
                $$
                """).update();
        db.sql("""
                CREATE TRIGGER test_fail_withdrawal_completion BEFORE UPDATE OF status ON deletion_operation
                FOR EACH ROW WHEN (NEW.id='%s'::uuid AND NEW.status='COMPLETED')
                EXECUTE FUNCTION test_fail_withdrawal_completion()
                """.formatted(bad)).update();
        try {
            for (int attempt = 0; attempt < 4; attempt++) {
                var token = new WithdrawalService.Purge(bad, attempt, 0);
                assertTrue(withdrawals.duePurges().contains(token));
                withdrawalJob.finalizeDueAccounts();
                assertFalse(withdrawals.duePurges().stream().anyMatch(item -> item.requestId().equals(bad)));
                assertEquals(1, db.sql("SELECT count(*) FROM app_user WHERE email=:email AND status='WITHDRAWAL_PENDING'")
                        .param("email", badEmail).query(Integer.class).single());
                if (attempt < 3) {
                    int delay = java.util.List.of(1, 5, 15).get(attempt);
                    assertTrue(db.sql("""
                            SELECT retry_count=:retry AND status='SCHEDULED' AND
                                next_run_at>clock_timestamp()+make_interval(mins=>:delay)-interval '10 seconds' AND
                                next_run_at<=clock_timestamp()+make_interval(mins=>:delay)
                            FROM deletion_operation WHERE id=:id
                            """).param("id", bad).param("retry", attempt + 1).param("delay", delay).query(Boolean.class).single());
                    withdrawals.recordPurgeFailure(token);
                    db.sql("UPDATE deletion_operation SET next_run_at=now()-interval '1 second' WHERE id=:id")
                            .param("id", bad).update();
                }
            }
            assertEquals("FAILED", db.sql("""
                    SELECT status FROM deletion_operation WHERE id=:id AND next_run_at IS NULL
                        AND retry_count=3 AND error_code='WITHDRAWAL_PURGE_FAILED'
                    """).param("id", bad).query(String.class).single());
            assertEquals("COMPLETED", db.sql("SELECT status FROM deletion_operation WHERE id=:id")
                    .param("id", healthy).query(String.class).single());
            assertEquals(before + 1, db.sql("SELECT completed_count FROM withdrawal_total").query(Long.class).single());
            org.mockito.Mockito.verify(discord).withdrawalCompleted(healthy, healthyEmail, before + 1);
        } finally {
            db.sql("DROP TRIGGER test_fail_withdrawal_completion ON deletion_operation").update();
            db.sql("DROP FUNCTION test_fail_withdrawal_completion()").update();
            db.sql("DELETE FROM app_user WHERE withdrawal_request_id=:bad OR withdrawal_request_id=:healthy")
                    .param("bad", bad).param("healthy", healthy).update();
            int completed = db.sql("DELETE FROM deletion_operation WHERE id=:id AND status='COMPLETED'")
                    .param("id", healthy).update();
            db.sql("UPDATE withdrawal_total SET completed_count=completed_count-:count").param("count", completed).update();
            db.sql("DELETE FROM deletion_operation WHERE id=:bad OR id=:healthy").param("bad", bad).param("healthy", healthy).update();
        }
    }

    @Test
    void dataDeletionRequiresReauthAndRetryDoesNotRemoveNewDiagnoses() throws Exception {
        var authorization = createAuthorization("127.0.0.11");
        var attempts = "/api/v1/diagnosis/attempts";
        var deletion = "/api/v1/account/data-deletion";
        long operations = db.sql("SELECT count(*) FROM deletion_operation WHERE kind='DATA_DELETION'")
                .query(Long.class).single();
        var requestId = UUID.randomUUID();
        var request = json.writeValueAsString(Map.of("requestId", requestId));
        var receipt = deletion + "/" + requestId;
        http.perform(get(receipt).header("Authorization", authorization)).andExpect(status().isNotFound());
        var started = http.perform(post(attempts).header("Authorization", authorization))
                .andExpect(status().isCreated()).andReturn();
        var attempt = json.readTree(started.getResponse().getContentAsString()).get("id").asString();
        var owner = db.sql("SELECT user_id FROM survey_attempt WHERE id=:attempt")
                .param("attempt", UUID.fromString(attempt)).query(UUID.class).single();
        var answers = json.writeValueAsString(Map.of("answers", java.util.Collections.nCopies(30, 3)));
        http.perform(post(attempts + "/" + attempt + "/submit").header("Authorization", authorization)
                        .contentType("application/json").content(answers)).andExpect(status().isOk());
        http.perform(post(deletion).header("Authorization", authorization).contentType("application/json").content(request))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/account/reauth").header("Authorization", authorization).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("password", "Finsight123"))))
                .andExpect(status().isNoContent());
        http.perform(post(deletion).header("Authorization", authorization)
                        .contentType("application/json").content(request))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        http.perform(post(deletion).header("Authorization", authorization).contentType("application/json").content(request))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.requestId").value(requestId.toString()));
        http.perform(get(deletion).header("Authorization", authorization)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()));
        http.perform(get("/api/v1/account/settings").header("Authorization", authorization)).andExpect(status().isOk());
        http.perform(get(attempts + "/" + attempt + "/result").header("Authorization", authorization))
                .andExpect(status().isNotFound());
        http.perform(post(attempts).header("Authorization", authorization))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATA_DELETION_PENDING"));
        assertEquals(30, db.sql("""
                SELECT count(*) FROM diagnosis_answer da JOIN diagnosis d ON d.id=da.diagnosis_id WHERE d.attempt_id=:attempt
                """).param("attempt", UUID.fromString(attempt)).query(Integer.class).single());
        var job = pendingDeletion(requestId);
        var operator = eventSession("OPERATOR", "Z".repeat(43));
        var adminUser = "/api/v1/admin/users/" + owner;
        var diagnosis = db.sql("SELECT id FROM diagnosis WHERE attempt_id=:attempt")
                .param("attempt", UUID.fromString(attempt)).query(UUID.class).single();
        http.perform(get(adminUser).cookie(operator)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.diagnosisCount").value(0)).andExpect(jsonPath("$.recentDiagnoses").isEmpty());
        http.perform(get(adminUser + "/diagnoses/" + diagnosis).cookie(operator)).andExpect(status().isNotFound());
        db.sql("""
                CREATE FUNCTION test_interrupt_service_data_purge() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'fixture interrupted the purge before commit'; END;
                $$
                """).update();
        db.sql("""
                CREATE TRIGGER test_interrupt_service_data_purge BEFORE UPDATE OF completed_at ON app_data_deletion
                FOR EACH ROW EXECUTE FUNCTION test_interrupt_service_data_purge()
                """).update();
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> dataDeletion.purge(job));
            dataDeletion.recordFailure(job);
            assertEquals(1, db.sql("""
                    SELECT retry_count FROM deletion_operation WHERE id=:id AND next_run_at>now() AND status='SCHEDULED'
                    """).param("id", job.operationId()).query(Integer.class).single());
            assertEquals(1, db.sql("SELECT count(*) FROM diagnosis WHERE id=:id")
                    .param("id", diagnosis).query(Integer.class).single());
            http.perform(get(attempts + "/" + attempt + "/result").header("Authorization", authorization))
                    .andExpect(status().isNotFound());
        } finally {
            db.sql("DROP TRIGGER test_interrupt_service_data_purge ON app_data_deletion").update();
            db.sql("DROP FUNCTION test_interrupt_service_data_purge()").update();
        }
        for (int attemptIndex = 1; attemptIndex <= 3; attemptIndex++) {
            db.sql("UPDATE deletion_operation SET next_run_at=now()-interval '1 second' WHERE id=:id")
                    .param("id", job.operationId()).update();
            dataDeletion.recordFailure(pendingDeletion(requestId));
        }
        http.perform(get(receipt).header("Authorization", authorization)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertAdminDeletionRequeue(job.operationId());
        dataDeletion.recordFailure(job);
        http.perform(get(attempts + "/" + attempt + "/result").header("Authorization", authorization))
                .andExpect(status().isNotFound());
        var retry = pendingDeletion(requestId);
        assertEquals(0, retry.retryCount());
        assertEquals(1, retry.requeueCount());
        assertFalse(dataDeletion.purge(job));
        assertTrue(dataDeletion.purge(retry));
        assertFalse(dataDeletion.purge(retry));
        var deleted = http.perform(get(receipt).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED")).andReturn();
        var completedAt = json.readTree(deleted.getResponse().getContentAsString()).get("completedAt").asString();
        db.sql("UPDATE app_session SET reauthenticated_at=now()-interval '10 minutes' WHERE user_id=:owner")
                .param("owner", owner).update();
        http.perform(get(receipt)).andExpect(status().isUnauthorized());
        http.perform(get(receipt).header("Authorization", createAuthorization("127.0.0.71")))
                .andExpect(status().isNotFound());
        http.perform(get(receipt).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.completedAt").value(completedAt));
        http.perform(get(attempts + "/" + attempt + "/result").header("Authorization", authorization))
                .andExpect(status().isNotFound());
        http.perform(get(attempts).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        assertEquals(0, db.sql("""
                SELECT count(*) FROM diagnosis_answer da JOIN diagnosis d ON d.id = da.diagnosis_id
                WHERE d.attempt_id = :attempt
                """).param("attempt", UUID.fromString(attempt)).query(Integer.class).single());
        var next = http.perform(post(attempts).header("Authorization", authorization))
                .andExpect(status().isCreated()).andReturn();
        var nextId = json.readTree(next.getResponse().getContentAsString()).get("id").asString();
        http.perform(post(deletion).header("Authorization", authorization).contentType("application/json").content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").value(completedAt));
        assertEquals(operations + 1, db.sql("""
                SELECT count(*) FROM deletion_operation WHERE kind='DATA_DELETION' AND status='COMPLETED'
                """).query(Long.class).single());
        assertEquals(1, db.sql("""
                SELECT count(*) FROM app_event e JOIN deletion_operation o ON o.id=e.event_id
                WHERE e.user_id=:owner AND e.event_name='USER_DELETION_REQUESTED' AND o.kind='DATA_DELETION'
                  AND e.occurred_at=o.requested_at AND e.received_at=o.requested_at
                """).param("owner", owner).query(Integer.class).single());
        http.perform(post(attempts + "/" + nextId + "/submit").header("Authorization", authorization)
                        .contentType("application/json").content(answers)).andExpect(status().isOk());
    }

    private DataDeletionService.Job pendingDeletion(UUID requestId) {
        var operation = db.sql("SELECT operation_id FROM app_data_deletion WHERE request_id=:id")
                .param("id", requestId).query(UUID.class).single();
        return dataDeletion.dueJobs().stream().filter(job -> job.operationId().equals(operation)).findFirst().orElseThrow();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void reviewedWithdrawalRequeuesOnlyTheStillPendingAccount() throws Exception {
        var operation = UUID.randomUUID();
        var user = UUID.randomUUID();
        db.sql("""
                INSERT INTO deletion_operation(id,kind,status,requested_at,due_at,completed_at,retry_count,error_code)
                VALUES (:id,'ACCOUNT_WITHDRAWAL','FAILED',now()-interval '15 days',now(),now(),3,'WITHDRAWAL_PURGE_FAILED')
                """).param("id", operation).update();
        db.sql("""
                INSERT INTO app_user(id,email,status,withdrawal_request_id,withdrawal_due_at)
                VALUES (:id,:email,'WITHDRAWAL_PENDING',:operation,now()-interval '1 day')
                """).param("id", user).param("email", user + "@example.test").param("operation", operation).update();
        assertAdminDeletionRequeue(operation);
        assertEquals("WITHDRAWAL_PENDING", db.sql("SELECT status FROM app_user WHERE id=:id")
                .param("id", user).query(String.class).single());
        assertFalse(withdrawals.finalizePurge(new WithdrawalService.Purge(operation, 0, 0)));
        assertTrue(withdrawals.finalizePurge(new WithdrawalService.Purge(operation, 0, 1)));
        assertEquals(0, db.sql("SELECT count(*) FROM app_user WHERE id=:id").param("id", user).query(Integer.class).single());
    }

    private void assertAdminDeletionRequeue(UUID operation) throws Exception {
        var privacy = eventSession("PRIVACY", UUID.randomUUID().toString().replace("-", "") + "a".repeat(11));
        var supervisor = eventSession("SUPER_ADMIN", UUID.randomUUID().toString().replace("-", "") + "a".repeat(11));
        var path = "/api/v1/admin/deletion-requests/" + operation;
        var csrf = http.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isOk()).andReturn().getResponse();
        var csrfBody = json.readTree(csrf.getContentAsString());
        var csrfCookie = csrf.getCookie("__Secure-FINSIGHT_ADMIN_CSRF");
        var key = UUID.randomUUID().toString();
        var body = json.writeValueAsString(Map.of("reason", "오류 원인 확인 후 재접수", "expectedRequeueCount", 0));
        java.util.function.BiFunction<jakarta.servlet.http.Cookie, String,
                org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> write =
                (session, requestKey) -> post(path + "/retry").secure(true).cookie(session, csrfCookie)
                        .header("Origin", "https://localhost:18443").header("Idempotency-Key", requestKey)
                        .header(csrfBody.path("headerName").asString(), csrfBody.path("token").asString())
                        .contentType("application/json").content(body);
        http.perform(get(path).cookie(privacy)).andExpect(status().isOk())
                .andExpect(jsonPath("$.retryable").value(true)).andExpect(jsonPath("$.requeueCount").value(0));
        http.perform(write.apply(supervisor, key)).andExpect(status().isForbidden());
        for (int repeat = 0; repeat < 2; repeat++) {
            http.perform(write.apply(privacy, key)).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("SCHEDULED")).andExpect(jsonPath("$.requeueCount").value(1))
                    .andExpect(jsonPath("$.retryCount").value(0)).andExpect(jsonPath("$.email").doesNotExist());
        }
        http.perform(write.apply(privacy, UUID.randomUUID().toString())).andExpect(status().isConflict());
        assertEquals(1, db.sql("""
                SELECT count(*) FROM audit_log WHERE target_id=:id AND action='DELETION_JOB_REQUEUED'
                    AND changes_safe->>'reason' IS NULL
                """).param("id", operation.toString()).query(Integer.class).single());
    }

    private void requestAndPurge(String authorization) throws Exception {
        assertTrue(dataDeletion.purge(requestDeletion(authorization)));
    }

    private DataDeletionService.Job requestDeletion(String authorization) throws Exception {
        var requestId = UUID.randomUUID();
        http.perform(post("/api/v1/account/data-deletion").header("Authorization", authorization)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("requestId", requestId))))
                .andExpect(status().isAccepted()).andExpect(header().exists("X-Finsight-Content-Epoch"));
        return pendingDeletion(requestId);
    }

    @Test
    void exportLinksRequireOwnershipAndStopWorkingAfterDeletion() throws Exception {
        var owner = createAuthorization("127.0.0.12");
        var other = createAuthorization("127.0.0.13");
        var path = "/api/v1/account/export";
        var start = http.perform(post("/api/v1/diagnosis/attempts").header("Authorization", owner))
                .andExpect(status().isCreated()).andReturn();
        var attempt = json.readTree(start.getResponse().getContentAsString()).get("id").asString();
        var responses = json.writeValueAsString(Map.of("answers", java.util.Collections.nCopies(30, 3)));
        http.perform(post("/api/v1/diagnosis/attempts/" + attempt + "/submit")
                        .header("Authorization", owner).contentType("application/json").content(responses))
                .andExpect(status().isOk());
        var ownerId = db.sql("SELECT user_id FROM survey_attempt WHERE id = :id")
                .param("id", UUID.fromString(attempt)).query(UUID.class).single();
        var diagnosisId = db.sql("SELECT id FROM diagnosis WHERE attempt_id = :id")
                .param("id", UUID.fromString(attempt)).query(UUID.class).single();
        http.perform(post("/api/v1/diagnosis/attempts/" + attempt + "/submit")
                        .header("Authorization", owner).contentType("application/json").content(responses))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(discord).surveyCompleted(diagnosisId, ownerId);
        var guides = "/api/v1/guides";
        http.perform(get(guides).param("attemptId", attempt).header("Authorization", other))
                .andExpect(status().isNotFound());
        http.perform(get(guides).param("attemptId", attempt).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guides.length()").value(4))
                .andExpect(jsonPath("$.guides[0].id").value("DEBT-1"));
        http.perform(post(guides + "/DEBT-1/view").param("attemptId", attempt).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false));
        for (var completed : java.util.List.of(true, true, false, true)) {
            http.perform(put(guides + "/DEBT-1/completion").param("attemptId", attempt)
                            .header("Authorization", owner).contentType("application/json")
                            .content(json.writeValueAsString(Map.of("completed", completed))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(completed));
        }
        assertEquals(1, db.sql("""
                SELECT count(*) FROM guide_activity g JOIN survey_attempt a ON a.user_id = g.user_id
                WHERE a.id = :attempt AND g.content_id = 'DEBT-1' AND g.completed_at IS NOT NULL
                """).param("attempt", UUID.fromString(attempt)).query(Integer.class).single());
        http.perform(post(path).header("Authorization", owner)).andExpect(status().isForbidden());
        http.perform(post("/api/v1/account/reauth").header("Authorization", owner)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("password", "Finsight123"))))
                .andExpect(status().isNoContent());
        var pending = http.perform(post(path).header("Authorization", owner))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        var id = json.readTree(pending.getResponse().getContentAsString()).get("id").asString();
        http.perform(post(path).header("Authorization", owner))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id));
        exportLeases.acquire(AppDataExports.QUEUE, UUID.fromString(id)).orElseThrow();
        assertFalse(exports.processNext());
        db.sql("UPDATE job_execution_lease SET expires_at=clock_timestamp()-interval '1 second' WHERE job_id=:id")
                .param("id", UUID.fromString(id)).update();
        assertTrue(exports.processNext());
        http.perform(get(path).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        http.perform(post(path + "/" + id + "/link").header("Authorization", other))
                .andExpect(status().isNotFound());
        var link = http.perform(post(path + "/" + id + "/link").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn();
        var url = json.readTree(link.getResponse().getContentAsString()).get("path").asString();
        var archive = http.perform(get(url)).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip")).andReturn().getResponse().getContentAsByteArray();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            assertEquals("진단.csv", zip.getNextEntry().getName());
            var csv = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("\ufeff"));
            assertTrue(csv.contains("\"Q30\""));
            assertTrue(csv.contains("\"50\""));
            assertEquals(2, csv.lines().count());
            assertEquals("가이드실천.csv", zip.getNextEntry().getName());
            assertTrue(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("부채부터 파악하기"));
        }
        http.perform(get(url + "0")).andExpect(status().isNotFound());
        var deletionJob = requestDeletion(owner);
        http.perform(get(url)).andExpect(status().isNotFound());
        http.perform(get(path).header("Authorization", owner)).andExpect(status().isNotFound());
        assertTrue(dataDeletion.purge(deletionJob));
        assertEquals(0, db.sql("SELECT count(*) FROM guide_activity WHERE user_id = :owner")
                .param("owner", ownerId).query(Integer.class).single());
        http.perform(get(url)).andExpect(status().isNotFound());
        http.perform(get(path).header("Authorization", owner)).andExpect(status().isNotFound());
    }

    @Test
    void comparisonExposesOnlyAllowedCellsAndDataDeletionInvalidatesIt() throws Exception {
        var owner = createAuthorization("127.0.0.14");
        var viewer = createAuthorization("127.0.0.15");
        var attempts = "/api/v1/diagnosis/attempts";
        var answers = json.writeValueAsString(Map.of("answers", java.util.Collections.nCopies(30, 3)));
        UUID sourceAttempt = null;
        for (String authorization : java.util.List.of(owner, viewer)) {
            var start = http.perform(post(attempts).header("Authorization", authorization))
                    .andExpect(status().isCreated()).andReturn();
            var attempt = UUID.fromString(json.readTree(start.getResponse().getContentAsString()).get("id").asString());
            if (authorization.equals(owner)) sourceAttempt = attempt;
            http.perform(post(attempts + "/" + attempt + "/submit").header("Authorization", authorization)
                            .contentType("application/json").content(answers)).andExpect(status().isOk());
        }
        var path = "/api/v1/comparison";
        http.perform(get(path).header("Authorization", viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.metrics").doesNotExist());

        var snapshot = UUID.randomUUID();
        var admin = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,'comparison@example.test','fixture','test',decode('00','hex'),'ACTIVE')
                """).param("id", admin).update();
        db.sql("""
                INSERT INTO comparison_snapshot
                    (id,type,period_start,period_end,formula_version,dataset_version,sample_n,metrics,status,approved_metrics)
                SELECT :id,'DEBT',current_date-29,current_date,a.formula_version,c.version,30,
                    '{"YFSI_MEAN":{"mean":55,"n":30},
                    "EXTERNAL_AREA_MEAN":{"DEBT":{"mean":60,"n":30},"INCOME":{"mean":45,"n":29}},
                    "PERCEIVED_STRESS_MEAN":{"mean":10,"n":30}}','APPROVED',ARRAY['YFSI_MEAN','EXTERNAL_AREA_MEAN']
                FROM survey_attempt a CROSS JOIN comparison_dataset c WHERE a.id=:attempt AND c.type='DEBT'
                """).param("id", snapshot).param("attempt", sourceAttempt).update();
        db.sql("""
                INSERT INTO comparison_snapshot_member
                SELECT :snapshot,a.user_id,d.id FROM survey_attempt a JOIN diagnosis d ON d.attempt_id=a.id
                WHERE a.id=:attempt
                """).param("snapshot", snapshot).param("attempt", sourceAttempt).update();
        db.sql("""
                INSERT INTO comparison_publication
                    (id,type,snapshot_id,allowed_metrics,starts_at,ends_at,status,approver_id)
                VALUES (:id,'DEBT',:snapshot,ARRAY['YFSI_MEAN','EXTERNAL_AREA_MEAN'],
                    now()-interval '1 minute',now()+interval '1 day','ACTIVE',:admin)
                """).param("id", UUID.randomUUID()).param("snapshot", snapshot).param("admin", admin).update();
        http.perform(get(path).header("Authorization", viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.metrics").doesNotExist());
        db.sql("UPDATE comparison_dataset SET min_sample=30 WHERE type='DEBT'").update();
        http.perform(get(path).header("Authorization", viewer))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.metrics.YFSI_MEAN").value(55))
                .andExpect(jsonPath("$.metrics.EXTERNAL_AREA_MEAN.DEBT").value(60))
                .andExpect(jsonPath("$.metrics.EXTERNAL_AREA_MEAN.INCOME").doesNotExist())
                .andExpect(jsonPath("$.metrics.PERCEIVED_STRESS_MEAN").doesNotExist());

        var insight = publishInsight(snapshot, viewer);
        http.perform(get(insight).header("Authorization", viewer))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.metric.DEBT").value(60))
                .andExpect(jsonPath("$.metric.INCOME").doesNotExist())
                .andExpect(jsonPath("$.card.helpfulCount").value(0));
        http.perform(get("/api/v1/insights").param("topic", "YFSI_MEAN").header("Authorization", viewer))
                .andExpect(jsonPath("$.items.length()").value(0));
        for (var helpful : java.util.List.of(true, true, false, true)) {
            http.perform(put(insight + "/helpful").header("Authorization", viewer).contentType("application/json")
                            .content(json.writeValueAsString(Map.of("revision", 1, "helpful", helpful))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.helpful").value(helpful))
                    .andExpect(jsonPath("$.card.helpfulCount").value(helpful ? 1 : 0));
        }
        var contentId = insight.substring(insight.lastIndexOf('/') + 1);
        assertEquals(3L, db.sql("SELECT count(*) FROM app_event WHERE event_name='HELPFUL_SET' AND content_id=:id")
                .param("id", contentId).query(Long.class).single());
        var impression = json.writeValueAsString(Map.of("eventId", UUID.randomUUID(), "action", "IMPRESSION", "revision", 1,
                "viewSessionId", UUID.randomUUID(), "occurredAt", java.time.OffsetDateTime.now()));
        for (int i = 0; i < 2; i++) {
            http.perform(post(insight + "/events").header("Authorization", viewer).contentType("application/json").content(impression))
                    .andExpect(status().isNoContent());
        }
        var insightView = json.writeValueAsString(Map.of("eventId", UUID.randomUUID(), "action", "VIEWED", "revision", 1,
                "occurredAt", java.time.OffsetDateTime.now()));
        http.perform(post(insight + "/events").header("Authorization", viewer).contentType("application/json").content(insightView))
                .andExpect(status().isNoContent());
        var performanceAnalyst = eventSession("ANALYST", "S".repeat(43));
        var performance = "/api/v1/admin/analytics/content-performance";
        http.perform(get(performance).cookie(performanceAnalyst)
                        .param("kind", "INSIGHT").param("contentId", contentId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].impressions").value(1))
                .andExpect(jsonPath("$.items[0].views").value(1)).andExpect(jsonPath("$.items[0].helpful").value(1));
        var yesterday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1);
        var earlier = yesterday.minusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        db.sql("UPDATE service_notice_revision SET starts_at=:stamp WHERE notice_id=:id AND revision_no=1")
                .param("stamp", earlier).param("id", UUID.fromString(contentId)).update();
        for (boolean reaction : java.util.List.of(true, false)) {
            db.sql("""
                    INSERT INTO app_event(event_name,user_id,content_kind,content_id,content_revision,content_type,
                        completion_value,occurred_at,received_at)
                    SELECT 'HELPFUL_SET',user_id,'INSIGHT',:id,1,'DEBT',:reaction,:stamp,:stamp
                    FROM app_event WHERE event_name='HELPFUL_SET' AND content_id=:id LIMIT 1
                    """).param("id", contentId).param("reaction", reaction)
                    .param("stamp", reaction ? earlier : earlier.plusDays(1)).update();
            http.perform(get(performance).cookie(performanceAnalyst).param("kind", "INSIGHT").param("contentId", contentId)
                            .param("from", yesterday.toString()).param("to", yesterday.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].helpful").value(reaction ? 1 : 0));
        }
        db.sql("UPDATE comparison_publication SET ends_at=now()-interval '1 second' WHERE snapshot_id=:id")
                .param("id", snapshot).update();
        http.perform(get(insight).header("Authorization", viewer)).andExpect(status().isNotFound());
        db.sql("UPDATE comparison_publication SET ends_at=now()+interval '1 day' WHERE snapshot_id=:id")
                .param("id", snapshot).update();

        http.perform(post("/api/v1/account/reauth").header("Authorization", owner).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("password", "Finsight123"))))
                .andExpect(status().isNoContent());
        var pending = requestDeletion(owner);
        http.perform(get(path).header("Authorization", viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("REBUILDING"))
                .andExpect(jsonPath("$.metrics").doesNotExist());
        http.perform(get(insight).header("Authorization", viewer)).andExpect(status().isNotFound());
        http.perform(get("/api/v1/insights").header("Authorization", viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        assertTrue(dataDeletion.purge(pending));
        http.perform(post("/api/v1/account/reauth").header("Authorization", viewer).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("password", "Finsight123")))).andExpect(status().isNoContent());
        requestAndPurge(viewer);
        assertEquals(0L, db.sql("SELECT count(*) FROM insight_helpful WHERE content_id=:id")
                .param("id", UUID.fromString(contentId)).query(Long.class).single());
    }

    private String publishInsight(UUID snapshot, String viewer) throws Exception {
        var root = "/api/v1/admin/contents";
        var operator = eventSession("OPERATOR", "Q".repeat(43));
        var supervisor = eventSession("SUPER_ADMIN", "R".repeat(43));
        var csrf = http.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isOk()).andReturn().getResponse();
        var csrfBody = json.readTree(csrf.getContentAsString());
        java.util.function.UnaryOperator<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> write =
                request -> request.secure(true).cookie(csrf.getCookie("__Secure-FINSIGHT_ADMIN_CSRF"))
                        .header("Origin", "https://localhost:18443")
                        .header(csrfBody.path("headerName").asString(), csrfBody.path("token").asString())
                        .contentType("application/json");
        http.perform(get(root + "/insight-sources").cookie(operator))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].snapshotId").value(snapshot.toString()));
        var fields = new java.util.HashMap<String, Object>(Map.of("title", "공개 행동 인사이트", "summary", "요약", "body", "본문",
                "sortOrder", 0, "types", java.util.List.of("FUTURE"), "snapshotId", snapshot,
                "metricKey", "EXTERNAL_AREA_MEAN", "insightExplanation", "공개 집계 설명"));
        http.perform(write.apply(post(root).cookie(operator)).content(json.writeValueAsString(Map.of("kind", "INSIGHT", "fields", fields))))
                .andExpect(status().is(422));
        fields.put("types", java.util.List.of("DEBT"));
        var draft = json.readTree(http.perform(write.apply(post(root).cookie(operator))
                        .content(json.writeValueAsString(Map.of("kind", "INSIGHT", "fields", fields))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var content = root + "/" + draft.path("id").asString();
        var app = "/api/v1/insights/" + draft.path("id").asString();
        var revision = draft.path("revisionId").asString();
        http.perform(get(content + "/preview").cookie(operator).param("revisionId", revision))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metricValue.DEBT").value(60))
                .andExpect(jsonPath("$.metricValue.INCOME").doesNotExist());
        http.perform(get(app).header("Authorization", viewer)).andExpect(status().isNotFound());
        long version = 0;
        for (var action : java.util.List.of("SUBMIT", "APPROVE", "PUBLISH")) {
            var role = action.equals("SUBMIT") ? operator : supervisor;
            http.perform(write.apply(post(content + "/transitions").cookie(role)).header("Idempotency-Key", "insight-" + action)
                            .content(json.writeValueAsString(Map.of("revisionId", revision, "action", action,
                                    "expectedVersion", version++, "serviceOnlyConfirmed", true))))
                    .andExpect(status().isOk());
        }
        return app;
    }

    @Test
    void monthlyReportUsesKoreanMonthBoundaryAndCountsCurrentGuideStates() throws Exception {
        var authorization = createAuthorization("127.0.0.16");
        var attempts = "/api/v1/diagnosis/attempts";
        var report = "/api/v1/diagnosis/monthly-report";
        var days = java.util.List.of("2026-01-31T14:59:59Z", "2026-01-31T15:00:00Z", "2026-02-27T01:00:00Z");
        var ids = new java.util.ArrayList<String>();
        for (int index = 0; index < days.size(); index++) {
            var started = http.perform(post(attempts).header("Authorization", authorization))
                    .andExpect(status().isCreated()).andReturn();
            var id = json.readTree(started.getResponse().getContentAsString()).get("id").asString();
            ids.add(id);
            var answers = new java.util.ArrayList<>(java.util.Collections.nCopies(30, 3));
            if (index == 2) answers.set(0, 5);
            http.perform(post(attempts + "/" + id + "/submit").header("Authorization", authorization)
                            .contentType("application/json").content(json.writeValueAsString(Map.of("answers", answers))))
                    .andExpect(status().isOk());
            db.sql("UPDATE diagnosis SET completed_at = :time WHERE attempt_id = :id")
                    .param("time", java.time.OffsetDateTime.parse(days.get(index)))
                    .param("id", UUID.fromString(id)).update();
        }
        db.sql("""
                INSERT INTO guide_activity(user_id,content_id,month_start,content_version,representative_type,title,completed_at)
                SELECT a.user_id, item.id, date '2026-02-01',1,'DEBT','test',item.completed
                FROM survey_attempt a CROSS JOIN (VALUES ('DEBT-1',now()),('DEBT-2',NULL)) item(id,completed)
                WHERE a.id = :id
                """).param("id", UUID.fromString(ids.getFirst())).update();
        http.perform(get(report).param("month", "2026-01").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.diagnosisCount").value(1))
                .andExpect(jsonPath("$.attemptId").value(ids.getFirst()))
                .andExpect(jsonPath("$.scoreChange").doesNotExist());
        http.perform(get(report).param("month", "2026-02").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.diagnosisCount").value(2))
                .andExpect(jsonPath("$.attemptId").value(ids.getLast()))
                .andExpect(jsonPath("$.scoreChange").value(2))
                .andExpect(jsonPath("$.guideViews").value(2))
                .andExpect(jsonPath("$.guideCompletions").value(1))
                .andExpect(jsonPath("$.provisional").value(false));
        http.perform(get(report).param("month", "2026-03").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void scheduledNewsKeepsTheCurrentRevisionUntilActivationAndHidesExpiredContentBeforeTheWorkerRuns() throws Exception {
        var auth = createAuthorization("127.0.0.72");
        var actor = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'fixture','test',decode('00','hex'),'ACTIVE')
                """).param("id", actor).param("email", actor + "@example.test").update();
        db.sql("INSERT INTO admin_role VALUES (:id,'SUPER_ADMIN')").param("id", actor).update();
        var zone = java.time.ZoneId.of("Asia/Seoul");
        var now = java.time.OffsetDateTime.now(zone);
        var tomorrow = now.toLocalDate().plusDays(1);
        var future = tomorrow.atTime(7, 0).atZone(zone).toOffsetDateTime();
        var type = kr.finsight.diagnosis.YfsiScoring.Pressure.FAMILY;
        var input = new kr.finsight.news.NewsEditorial.Draft("https://www.hankyung.com/article/8754321",
                "현재 공개 기사", "요약", "본문", "관련 이유", "작은 행동", now.minusHours(1), java.util.List.of(type));
        var draft = news.draft(actor, null, input);
        var approved = approveNews(actor, draft);
        var published = changeNews(actor, approved, kr.finsight.news.NewsEditorial.Action.PUBLISH, null, null);
        var previousVersion = news.editionOptions(now.toLocalDate(), type).version();
        var todayEdition = news.edition(now.toLocalDate(), type, java.util.List.of(newsSlot(published)), previousVersion);
        var stableEdition = news.edition(now.toLocalDate(), type, todayEdition.slots(), todayEdition.version());
        assertEquals(todayEdition.version(), stableEdition.version());
        var revisedInput = new kr.finsight.news.NewsEditorial.Draft(input.sourceUrl(), "예약된 새 버전",
                input.summary(), input.body(), input.reason(), input.action(), input.articlePublishedAt(), input.types());
        var approvedRevision = approveNews(actor, news.draft(actor, published.id(), revisedInput));
        var scheduled = changeNews(actor, approvedRevision, kr.finsight.news.NewsEditorial.Action.SCHEDULE, future, future.plusHours(3));
        assertEquals("SCHEDULED", scheduled.status());
        assertTrue(news.editionOptions(tomorrow, type).options().stream()
                .anyMatch(option -> option.contentId().equals(published.id()) && option.revisionId() == scheduled.revision()
                        && option.title().equals(revisedInput.title())));
        news.edition(tomorrow, type, java.util.List.of(newsSlot(scheduled)), 0L);
        var due = new kr.finsight.news.NewsEditorial.Due(scheduled.id(), scheduled.revision());
        var url = "/api/v1/news/" + scheduled.id();
        assertNull(news.processPublication(due));
        http.perform(get(url).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(published.revision()));
        var canceled = changeNews(actor, scheduled, kr.finsight.news.NewsEditorial.Action.CANCEL_SCHEDULE, null, null);
        assertEquals("APPROVED", canceled.status());
        assertNull(canceled.startsAt());

        changeNews(actor, canceled, kr.finsight.news.NewsEditorial.Action.SCHEDULE, future, future.plusHours(3));
        db.sql("DELETE FROM admin_role WHERE admin_id=:id AND role='SUPER_ADMIN'").param("id", actor).update();
        db.sql("""
                UPDATE news_revision SET starts_at=clock_timestamp()-interval '2 minutes'
                WHERE article_id=:id AND revision=:revision
                """).param("id", scheduled.id()).param("revision", scheduled.revision()).update();
        assertNull(news.processPublication(due));
        var revoked = news.lookup(due.articleId(), due.revision());
        assertEquals("APPROVED", revoked.status());
        assertEquals(published.revision(), revoked.publishedRevision());

        db.sql("INSERT INTO admin_role VALUES (:id,'SUPER_ADMIN')").param("id", actor).update();
        changeNews(actor, revoked, kr.finsight.news.NewsEditorial.Action.SCHEDULE, future, future.plusHours(3));
        db.sql("""
                UPDATE news_revision SET starts_at=clock_timestamp()-interval '2 minutes'
                WHERE article_id=:id AND revision=:revision
                """).param("id", scheduled.id()).param("revision", scheduled.revision()).update();
        assertTrue(news.duePublications().contains(due));
        assertEquals(scheduled.revision(), news.processPublication(due).article().publishedRevision());
        assertNull(news.processPublication(due));
        assertEquals("UNPUBLISHED", news.lookup(published.id(), published.revision()).status());
        http.perform(get(url).header("Authorization", auth)).andExpect(status().isNotFound());
        assertEquals(todayEdition.slots(), news.editionOptions(now.toLocalDate(), type).slots());
        assertEquals(409, assertThrows(ResponseStatusException.class, () ->
                news.edition(now.toLocalDate(), type, java.util.List.of(newsSlot(scheduled)), previousVersion)).getStatusCode().value());
        var replaced = news.edition(now.toLocalDate(), type, java.util.List.of(newsSlot(scheduled)), todayEdition.version());
        assertEquals(todayEdition.version() + 1, replaced.version());
        http.perform(get(url).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(scheduled.revision()));
        db.sql("""
                UPDATE news_revision SET ends_at=clock_timestamp()
                WHERE article_id=:id AND revision=:revision
                """).param("id", scheduled.id()).param("revision", scheduled.revision()).update();
        http.perform(get(url).header("Authorization", auth)).andExpect(status().isNotFound());
        assertTrue(news.editionOptions(now.toLocalDate(), type).options().stream()
                .noneMatch(option -> option.contentId().equals(published.id())));
        assertNull(news.processPublication(due));
        assertEquals("EXPIRED", news.lookup(due.articleId(), due.revision()).status());
        assertNull(news.lookup(due.articleId(), due.revision()).publishedRevision());
    }

    private kr.finsight.news.NewsEditorial.Article approveNews(UUID actor, kr.finsight.news.NewsEditorial.Article draft) {
        var review = changeNews(actor, draft, kr.finsight.news.NewsEditorial.Action.SUBMIT, null, null);
        return changeNews(actor, review, kr.finsight.news.NewsEditorial.Action.APPROVE, null, null);
    }

    private static kr.finsight.news.NewsEditorial.EditionSlot newsSlot(kr.finsight.news.NewsEditorial.Article article) {
        return new kr.finsight.news.NewsEditorial.EditionSlot(1, article.id(), article.revision());
    }

    private kr.finsight.news.NewsEditorial.Article changeNews(UUID actor, kr.finsight.news.NewsEditorial.Article article,
            kr.finsight.news.NewsEditorial.Action action, java.time.OffsetDateTime starts, java.time.OffsetDateTime ends) {
        return news.transition(actor, article.id(), article.revision(), new kr.finsight.news.NewsEditorial.Change(
                action, article.version(), "테스트 기사 상태 변경을 확인합니다", starts, ends));
    }

    @Test
    void newsFeedOnlyShowsPublishedEditionAndHidesUnpublishedDetail() throws Exception {
        var auth = createAuthorization("127.0.0.17");
        var editor = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,'news@example.test','fixture','test',decode('00','hex'),'ACTIVE')
                """).param("id", editor).update();
        var now = java.time.OffsetDateTime.now();
        var input = new kr.finsight.news.NewsEditorial.Draft("https://www.hankyung.com/article/123456?utm_source=test",
                "테스트 기사", "요약", "본문", "관련 이유", "작은 행동", now.minusHours(1),
                java.util.List.of(kr.finsight.diagnosis.YfsiScoring.Pressure.values()));
        var item = news.draft(editor, null, input);
        assertEquals("https://www.hankyung.com/article/123456", item.sourceUrl());
        var path = "/api/v1/news";
        http.perform(get(path).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.articles.length()").value(0));
        var review = news.transition(editor, item.id(), item.revision(), new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.SUBMIT, item.version(), null, null, null));
        var approved = news.transition(editor, item.id(), item.revision(), new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.APPROVE, review.version(), "단독 운영 승인 사유 테스트", null, null));
        var published = news.transition(editor, item.id(), item.revision(), new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.PUBLISH, approved.version(), "단독 운영 게시 사유 테스트", null, null));
        var archivePublished = new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.ARCHIVE, published.version(), null, null, null);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> news.transition(editor, item.id(), item.revision(), archivePublished, true, true)).getStatusCode().value());
        http.perform(get(path).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.articles.length()").value(0));
        var date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var types = kr.finsight.diagnosis.YfsiScoring.Pressure.values();
        var selected = new java.util.EnumMap<kr.finsight.diagnosis.YfsiScoring.Pressure, String>(
                kr.finsight.diagnosis.YfsiScoring.Pressure.class);
        for (var type : types) {
            news.edition(date, type, java.util.List.of(newsSlot(item)), news.editionOptions(date, type).version());
            selected.put(type, item.sourceUrl());
            if (selected.size() < types.length) {
                org.mockito.Mockito.verify(discord, org.mockito.Mockito.never())
                        .newsSelected(org.mockito.ArgumentMatchers.eq(date), org.mockito.ArgumentMatchers.anyMap());
            }
        }
        org.mockito.Mockito.verify(discord).newsSelected(date, selected);
        http.perform(get(path).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.articles[0].title").value("테스트 기사"))
                .andExpect(jsonPath("$.articles[0].reason").doesNotExist());
        http.perform(get(path).param("edition", date.toString()).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.articles[0].editionDate").value(date.toString()));
        http.perform(get(path + "/" + item.id()).param("edition", date.toString()).header("Authorization", auth))
                .andExpect(status().isOk());
        var otherViewer = createAuthorization("127.0.0.62");
        var analyst = eventSession("ANALYST", "H".repeat(43));
        var report = "/api/v1/admin/analytics/content-performance";
        var eventPath = path + "/" + item.id() + "/events";
        var viewSession = UUID.randomUUID();
        http.perform(post(eventPath).param("edition", date.toString()).header("Authorization", auth)
                        .contentType("application/json").content(json.writeValueAsString(Map.of(
                                "eventId", UUID.randomUUID(), "action", "VIEWED", "revision", item.revision()+1,
                                "occurredAt", java.time.OffsetDateTime.now()))))
                .andExpect(status().isNotFound());
        for (int count = 0; count < 2; count++) {
            contentEvent(eventPath, "edition", date.toString(), auth, item.revision(), viewSession);
        }
        contentEvent(eventPath, "edition", date.toString(), auth, item.revision(), null);
        contentEvent(eventPath, "edition", date.toString(), otherViewer, item.revision(), null);
        assertEquals(1L, db.sql("""
                SELECT count(*) FROM app_event WHERE content_id=:id AND event_name='CONTENT_IMPRESSION'
                """).param("id", item.id().toString()).query(Long.class).single());
        http.perform(get(report).cookie(analyst).param("contentId", item.id().toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].impressions").value(1))
                .andExpect(jsonPath("$.items[0].views").value(2))
                .andExpect(jsonPath("$.items[0].ctrNumerator").value(1))
                .andExpect(jsonPath("$.items[0].ctr").value(100.0))
                .andExpect(jsonPath("$.items[0].helpful").isEmpty())
                .andExpect(jsonPath("$.items[0].completions").isEmpty());
        var unpublished = news.transition(editor, item.id(), item.revision(), new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.UNPUBLISH, published.version(), "테스트 기사를 공개 중지합니다", null, null));
        http.perform(get(path + "/" + item.id()).header("Authorization", auth)).andExpect(status().isNotFound());
        http.perform(get(path).param("edition", date.toString()).header("Authorization", auth))
                .andExpect(status().isNotFound());
        var archive = new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.ARCHIVE, unpublished.version(), null, null, null);
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> news.transition(editor, item.id(), item.revision(), archive)).getStatusCode().value());
        var archived = news.transition(editor, item.id(), item.revision(), archive, false, true);
        assertEquals("ARCHIVED", archived.status());
        var filter = new kr.finsight.content.EditorialPage.Filter("ARCHIVED", input.title(), null, null, null, null);
        assertEquals(item.id(), news.page(filter, 1, 20).items().getFirst().id());
        assertTrue(news.list().stream().noneMatch(row -> row.id().equals(item.id())));
        var copy = news.draft(editor, item.id(), input);
        var archiveDraft = new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.ARCHIVE, copy.version(), null, null, null);
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> news.transition(UUID.randomUUID(), copy.id(), copy.revision(), archiveDraft)).getStatusCode().value());
        assertEquals("ARCHIVED", news.transition(editor, copy.id(), copy.revision(), archiveDraft).status());
        var next = news.draft(editor, item.id(), input);
        assertEquals(3, next.revision());
        assertEquals("DRAFT", next.status());
        assertEquals("ARCHIVED", news.lookup(item.id(), item.revision()).status());
        http.perform(get(path + "/" + item.id()).header("Authorization", auth)).andExpect(status().isNotFound());
        var nextReview = news.transition(editor, next.id(), next.revision(), new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.SUBMIT, next.version(), null, null, null));
        var nextApproval = news.transition(editor, next.id(), next.revision(), new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.APPROVE, nextReview.version(), "새로운 기사 버전 승인 테스트", null, null));
        var nextPublished = news.transition(editor, next.id(), next.revision(), new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.PUBLISH, nextApproval.version(), "새로운 기사 버전 게시 테스트", null, null));
        news.edition(date, types[0], java.util.List.of(newsSlot(nextPublished)), news.editionOptions(date, types[0]).version());
        contentEvent(eventPath, "edition", date.toString(), auth, next.revision(), UUID.randomUUID());
        contentEvent(eventPath, "edition", date.toString(), auth, next.revision(), null);
        http.perform(get(report).cookie(analyst).param("contentId", item.id().toString()).param("combineVersions", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].impressions").value(1)).andExpect(jsonPath("$.items[0].views").value(2));
        http.perform(get(report).cookie(analyst).param("contentId", item.id().toString())
                        .param("revision", String.valueOf(next.revision())))
                .andExpect(jsonPath("$.items[0].impressions").value(1)).andExpect(jsonPath("$.items[0].views").value(1));
        var oldTime = java.time.OffsetDateTime.now().minusSeconds(1);
        http.perform(post("/api/v1/account/reauth").header("Authorization", auth)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("password", "Finsight123"))))
                .andExpect(status().isNoContent());
        requestAndPurge(auth);
        var late = UUID.randomUUID();
        http.perform(post(eventPath).param("edition", date.toString()).header("Authorization", auth)
                        .contentType("application/json").content(json.writeValueAsString(Map.of(
                                "eventId", late, "action", "VIEWED", "revision", next.revision(), "occurredAt", oldTime))))
                .andExpect(status().isNoContent()).andExpect(header().string("X-Finsight-Content-Epoch", "1"));
        assertFalse(db.sql("SELECT EXISTS(SELECT 1 FROM app_event WHERE event_id=:id)")
                .param("id", late).query(Boolean.class).single());
        var skewed = UUID.randomUUID();
        http.perform(post(eventPath).param("edition", date.toString()).header("Authorization", auth)
                        .contentType("application/json").content(json.writeValueAsString(Map.of(
                                "eventId", skewed, "action", "VIEWED", "revision", next.revision(),
                                "occurredAt", oldTime.plusHours(1), "contentEventEpoch", 0))))
                .andExpect(status().isNoContent()).andExpect(header().string("X-Finsight-Content-Epoch", "1"));
        assertFalse(db.sql("SELECT EXISTS(SELECT 1 FROM app_event WHERE event_id=:id)")
                .param("id", skewed).query(Boolean.class).single());
        http.perform(get(report).cookie(analyst).param("contentId", item.id().toString()).param("combineVersions", "true"))
                .andExpect(jsonPath("$.items[0].impressions").value(0))
                .andExpect(jsonPath("$.items[0].views").value(1)).andExpect(jsonPath("$.items[0].ctr").isEmpty());
        var fresh = UUID.randomUUID();
        http.perform(post(eventPath).param("edition", date.toString()).header("Authorization", auth)
                        .contentType("application/json").content(json.writeValueAsString(Map.of(
                                "eventId", fresh, "action", "VIEWED", "revision", next.revision(),
                                "occurredAt", oldTime.minusHours(1), "contentEventEpoch", 1))))
                .andExpect(status().isNoContent());
        assertTrue(db.sql("SELECT clock_adjusted AND occurred_at=received_at FROM app_event WHERE event_id=:id")
                .param("id", fresh).query(Boolean.class).single());
        news.transition(editor, next.id(), next.revision(), new kr.finsight.news.NewsEditorial.Change(
                kr.finsight.news.NewsEditorial.Action.UNPUBLISH, nextPublished.version(), "테스트 기사를 공개 중지합니다", null, null));
    }

    private void contentEvent(String path, String param, String key, String auth, int revision, UUID viewSession) throws Exception {
        var event = new java.util.HashMap<String, Object>(Map.of("eventId", UUID.randomUUID(),
                "action", viewSession == null ? "VIEWED" : "IMPRESSION", "revision", revision,
                "occurredAt", java.time.OffsetDateTime.now()));
        if (viewSession != null) event.put("viewSessionId", viewSession);
        http.perform(post(path).param(param, key).header("Authorization", auth).contentType("application/json")
                .content(json.writeValueAsString(event))).andExpect(status().isNoContent());
    }

    @Test
    void passwordResetResendInvalidatesPreviousLinkAndCompletionRevokesSessions() throws Exception {
        var email = UUID.randomUUID() + "@example.test";
        signup.send(email, "127.0.0.44");
        assertEquals(SignupService.Outcome.CREATED, signup.register(registration(email, mail.code)));
        var session = appSessions.login(email, "Finsight123", "127.0.0.44").tokens();
        var path = "/api/v1/auth/password-reset";
        var request = post(path + "/request").contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email)));
        http.perform(request).andExpect(status().isAccepted()).andExpect(content().string(""));
        var firstLink = mail.resetLink;
        assertTrue(firstLink.startsWith("http://localhost:18081/#/password-reset?token="));
        http.perform(post(path + "/request").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", UUID.randomUUID() + "@example.test"))))
                .andExpect(status().isAccepted()).andExpect(content().string(""));
        assertEquals(firstLink, mail.resetLink);
        http.perform(request).andExpect(status().isTooManyRequests());
        db.sql("UPDATE app_password_reset_request SET requested_at=requested_at - interval '61 seconds'").update();
        http.perform(request).andExpect(status().isAccepted());
        var token = mail.resetLink.split("token=", 2)[1];
        var previous = firstLink.split("token=", 2)[1];
        var newPassword = "Renewed123";
        http.perform(post(path + "/complete").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("token", previous, "password", newPassword))))
                .andExpect(status().isGone());
        var complete = post(path + "/complete").contentType("application/json")
                .content(json.writeValueAsString(Map.of("token", token, "password", newPassword)));
        db.sql("INSERT INTO app_login_failure VALUES (:email, 5, now() + interval '15 minutes')")
                .param("email", email).update();
        http.perform(complete).andExpect(status().isNoContent());
        http.perform(complete).andExpect(status().isGone());
        http.perform(get("/api/v1/diagnosis/attempts").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized());
        assertNull(appSessions.refresh(session.refreshToken()));
        assertNull(appSessions.login(email, "Finsight123", "127.0.0.44").tokens());
        assertNotNull(appSessions.login(email, newPassword, "127.0.0.44").tokens());
    }

    @Test
    void accountSettingsKeepResearchHistoryAndIsolateProfileChanges() throws Exception {
        var auth = createAuthorization("127.0.0.45");
        var other = createAuthorization("127.0.0.46");
        var path = "/api/v1/account/settings";
        http.perform(get(path)).andExpect(status().isUnauthorized());
        http.perform(get(path).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.lastLoginAt").isString()).andExpect(jsonPath("$.profile.debt").value("NO"));
        var attempts = "/api/v1/diagnosis/attempts";
        var attempt = json.readTree(http.perform(post(attempts).header("Authorization", auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asString();
        http.perform(post(attempts + "/" + attempt + "/submit").header("Authorization", auth)
                        .contentType("application/json").content(json.writeValueAsString(
                                Map.of("answers", java.util.Collections.nCopies(30, 3)))))
                .andExpect(status().isOk());
        var profile = new java.util.HashMap<>(registration("unused@example.test", "000000").profile());
        profile.put("debt", "YES");
        http.perform(put(path + "/profile").header("Authorization", auth)
                        .contentType("application/json").content(json.writeValueAsString(profile)))
                .andExpect(status().isBadRequest());
        profile.put("debtType", "STUDENT");
        http.perform(put(path + "/profile").header("Authorization", auth)
                        .contentType("application/json").content(json.writeValueAsString(profile)))
                .andExpect(status().isNoContent());
        http.perform(get(path).header("Authorization", auth))
                .andExpect(jsonPath("$.profile.debtType").value("STUDENT"));
        http.perform(get(path).header("Authorization", other))
                .andExpect(jsonPath("$.profile.debt").value("NO"));
        assertEquals("NO", db.sql("SELECT profile_snapshot->>'debt' FROM diagnosis WHERE attempt_id=:attempt")
                .param("attempt", UUID.fromString(attempt)).query(String.class).single());
        for (var granted : java.util.List.of(true, true, false)) {
            http.perform(put(path + "/consents/research").header("Authorization", auth)
                            .contentType("application/json").content(json.writeValueAsString(Map.of(
                                    "version", PolicyDocuments.PREVIEW_VERSION, "granted", granted))))
                    .andExpect(status().isNoContent());
        }
        http.perform(get(path + "/consents").header("Authorization", auth))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].method").value("APP_SETTINGS"))
                .andExpect(jsonPath("$[0].granted").value(false))
                .andExpect(jsonPath("$[1].granted").value(true));
        http.perform(get(path + "/consents").header("Authorization", other))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void notificationInboxPaginatesFiltersAndIsolatesReadState() throws Exception {
        var auth = createAuthorization("127.0.0.47");
        var other = createAuthorization("127.0.0.48");
        var path = "/api/v1/notifications";
        http.perform(get(path)).andExpect(status().isUnauthorized());
        http.perform(get(path + "/preferences").header("Authorization", auth))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.economy").value(false));
        for (var change : java.util.List.of(Map.of("setting", "ENABLED", "enabled", true),
                Map.of("setting", "ECONOMY", "enabled", true), Map.of("setting", "ENABLED", "enabled", false))) {
            http.perform(put(path + "/preferences").header("Authorization", auth).contentType("application/json")
                            .content(json.writeValueAsString(change))).andExpect(status().isOk());
        }
        http.perform(get(path + "/preferences").header("Authorization", auth))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.economy").value(true));
        http.perform(get(path + "/preferences").header("Authorization", other))
                .andExpect(jsonPath("$.economy").value(false));

        var userId = UUID.fromString(json.readTree(java.util.Base64.getUrlDecoder().decode(
                auth.substring(7).split("\\.")[1])).path("sub").asString());
        db.sql("""
                INSERT INTO app_notification (id,user_id,kind,event_key,title,body,created_at)
                SELECT gen_random_uuid(), :user,
                    CASE WHEN n <= 22 THEN 'PERIODIC' ELSE 'ECONOMY' END, n::text, 'test title','test body',
                    now() - CASE WHEN n=22 THEN interval '91 days' ELSE interval '1 hour' END
                FROM generate_series(1,23) n
                """).param("user", userId).update();
        var result = http.perform(get(path).param("filter", "DIAGNOSIS").header("Authorization", auth))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(20)).andExpect(jsonPath("$.unread").value(true))
                .andReturn();
        var first = json.readTree(result.getResponse().getContentAsString());
        var id = first.path("items").get(0).path("id").asString();
        http.perform(get(path).param("filter", "DIAGNOSIS").param("before", first.path("nextCursor").asString())
                        .header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextCursor").isEmpty());
        http.perform(get(path).param("filter", "ECONOMY").header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(1));
        http.perform(get(path).header("Authorization", other))
                .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.unread").value(false));
        http.perform(put(path + "/" + id + "/read").header("Authorization", other)).andExpect(status().isNotFound());
        for (int repeat = 0; repeat < 2; repeat++) {
            http.perform(put(path + "/" + id + "/read").header("Authorization", auth)).andExpect(status().isNoContent());
        }
        http.perform(get(path).param("filter", "DIAGNOSIS").header("Authorization", auth))
                .andExpect(jsonPath("$.items[0].read").value(true));
    }

    @Test
    void notificationExportTargetsAreBoundToTheOwnerAndExpire() throws Exception {
        var auth = createAuthorization("127.0.0.49");
        var other = createAuthorization("127.0.0.50");
        var user = UUID.fromString(json.readTree(java.util.Base64.getUrlDecoder().decode(
                auth.substring(7).split("\\.")[1])).path("sub").asString());
        var export = UUID.randomUUID();
        var notice = UUID.randomUUID();
        db.sql("""
                INSERT INTO app_data_export(id,user_id,status,expires_at,archive,source_epoch)
                VALUES (:id,:user,'COMPLETED',now()+interval '1 hour',decode('00','hex'),0)
                """).param("id", export).param("user", user).update();
        db.sql("""
                INSERT INTO app_notification(id,user_id,kind,event_key,title,body)
                VALUES (:id,:user,'EXPORT',:export,'fixture','fixture')
                """).param("id", notice).param("user", user).param("export", export.toString()).update();
        var target = "/api/v1/notifications/" + notice + "/target";
        var file = "/api/v1/account/export/" + export;
        http.perform(get(target).header("Authorization", other)).andExpect(status().isNotFound());
        http.perform(get(file).header("Authorization", other)).andExpect(status().isNotFound());
        http.perform(get(target).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.resource").value(export.toString()));
        http.perform(get(file).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(export.toString()));
        db.sql("UPDATE app_data_export SET expires_at=now()-interval '1 second' WHERE id=:id").param("id", export).update();
        http.perform(get(target).header("Authorization", auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.resource").isEmpty());
        http.perform(get(file).header("Authorization", auth)).andExpect(status().isNotFound());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void noticeTargetChecksOwnershipAndTheCurrentPublicationWindow() throws Exception {
        var owner = createAuthorization("127.0.0.59");
        var other = createAuthorization("127.0.0.60");
        var user = UUID.fromString(json.readTree(java.util.Base64.getUrlDecoder().decode(
                owner.substring(7).split("\\.")[1])).path("sub").asString());
        var editor = UUID.randomUUID();
        var notice = UUID.randomUUID();
        var revision = UUID.randomUUID();
        var notification = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'fixture','fixture',decode('00','hex'),'ACTIVE')
                """).param("id", editor).param("email", editor + "@example.test").update();
        db.sql("INSERT INTO service_notice(id) VALUES (:id)").param("id", notice).update();
        db.sql("""
                INSERT INTO service_notice_revision(id,notice_id,revision_no,author_id,status,title,summary,body,sort_order,starts_at)
                VALUES (:id,:notice,1,:editor,'PUBLISHED','점검 일정','서비스 점검','공지 본문',0,now()-interval '1 hour')
                """).param("id", revision).param("notice", notice).param("editor", editor).update();
        db.sql("UPDATE service_notice SET published_revision_id=:revision WHERE id=:id")
                .param("revision", revision).param("id", notice).update();
        db.sql("""
                INSERT INTO app_notification(id,user_id,kind,event_key,title,body)
                VALUES (:id,:user,'NOTICE',:revision,'이용 안내','공지에서 확인')
                """).param("id", notification).param("user", user).param("revision", revision.toString()).update();
        var target = "/api/v1/notifications/" + notification + "/target";
        http.perform(get(target).header("Authorization", other)).andExpect(status().isNotFound());
        http.perform(get(target).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.notice.body").value("공지 본문"));
        db.sql("UPDATE service_notice SET published_revision_id=NULL WHERE id=:id").param("id", notice).update();
        http.perform(get(target).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("UNPUBLISHED")).andExpect(jsonPath("$.notice").doesNotExist());
        db.sql("UPDATE service_notice_revision SET ends_at=now()-interval '1 minute' WHERE id=:id")
                .param("id", revision).update();
        http.perform(get(target).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("EXPIRED")).andExpect(jsonPath("$.notice").doesNotExist());
    }

    @Test
    void foregroundActivityRequiresAuthenticationAndDeduplicatesSameDay() throws Exception {
        var auth = createAuthorization("127.0.0.51");
        var user = UUID.fromString(json.readTree(java.util.Base64.getUrlDecoder().decode(
                auth.substring(7).split("\\.")[1])).path("sub").asString());
        var path = "/api/v1/account/activity";
        http.perform(post(path)).andExpect(status().isUnauthorized());
        for (int i = 0; i < 2; i++) {
            http.perform(post(path).header("Authorization", auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.date").isString());
        }
        assertEquals(1, db.sql("SELECT count(*) FROM app_foreground_day WHERE user_id=:id")
                .param("id", user).query(Integer.class).single());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void operatingEventsFilterByJourneyDeduplicateAndDoNotRecreateDeletedHistory() throws Exception {
        var bearer = createAuthorization("127.0.0.61");
        var user = UUID.fromString(json.readTree(java.util.Base64.getUrlDecoder().decode(
                bearer.substring(7).split("\\.")[1])).path("sub").asString());
        var events = "/api/v1/admin/events";
        var operator = eventSession("OPERATOR", "E".repeat(43));
        var analyst = eventSession("ANALYST", "F".repeat(43));
        var supervisor = eventSession("SUPER_ADMIN", "G".repeat(43));
        http.perform(get(events)).andExpect(status().isUnauthorized());
        http.perform(get(events).cookie(analyst)).andExpect(status().isForbidden());
        http.perform(get(events).cookie(supervisor)).andExpect(status().isForbidden());
        var journey = UUID.randomUUID();
        var attempts = "/api/v1/diagnosis/attempts";
        var journeyBody = json.writeValueAsString(Map.of("journeyId", journey));
        var started = http.perform(post(attempts).header("Authorization", bearer)
                        .contentType("application/json").content(journeyBody))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var attempt = json.readTree(started.getContentAsString()).path("id").asString();
        var question = UUID.randomUUID();
        var recorded = java.time.OffsetDateTime.now();
        var payload = new java.util.HashMap<String, Object>(Map.of("eventId", question, "kind", "COMPLETED",
                "questionNo", 2, "activeMs", 415, "occurredAt", recorded.minusHours(1)));
        payload.put("response", "excluded-input");
        http.perform(post(attempts + "/" + attempt + "/question-events").header("Authorization", bearer)
                        .contentType("application/json").content(json.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
        payload.remove("response");
        var questionBody = json.writeValueAsString(payload);
        for (int repeat = 0; repeat < 2; repeat++) {
            http.perform(post(attempts + "/" + attempt + "/question-events").header("Authorization", bearer)
                            .contentType("application/json").content(questionBody))
                    .andExpect(status().isNoContent());
        }
        http.perform(post(attempts + "/" + attempt + "/submit").header("Authorization", bearer)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("answers",
                                java.util.Collections.nCopies(30, 3)))))
                .andExpect(status().isOk());
        var view = UUID.randomUUID();
        for (var id : java.util.List.of(question, view)) {
            http.perform(post(attempts + "/" + attempt + "/views").header("Authorization", bearer)
                            .contentType("application/json").content(json.writeValueAsString(
                                    Map.of("eventId", id, "occurredAt", recorded.minusSeconds(30)))))
                    .andExpect(status().isNoContent());
        }
        var response = http.perform(get(events).cookie(operator).param("eventId", question.toString())
                        .param("attemptId", attempt).param("journeyId", journey.toString())
                        .param("userId", user.toString()).param("type", "QUESTION_COMPLETED"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].clockAdjusted").value(true))
                .andExpect(jsonPath("$.items[0].properties.questionNo").value(2))
                .andExpect(jsonPath("$.items[0].properties.activeMs").value(415))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("excluded-input"));
        assertFalse(response.contains("@example.test"));
        assertTrue(db.sql("SELECT occurred_at=received_at FROM app_event WHERE event_id=:id")
                .param("id", question).query(Boolean.class).single());
        assertEquals(0L, db.sql("SELECT count(*) FROM diagnosis_view_event WHERE id=:id")
                .param("id", question).query(Long.class).single());
        http.perform(get(events).cookie(operator).param("eventId", view.toString()))
                .andExpect(jsonPath("$.items[0].clockAdjusted").value(false))
                .andExpect(jsonPath("$.items[0].eventName").value("RESULT_VIEWED"));
        http.perform(get(events).cookie(operator).param("from", "2001-01-01").param("to", "2001-01-03")
                        .param("userId", user.toString())).andExpect(jsonPath("$.total").value(0));

        http.perform(post("/api/v1/account/reauth").header("Authorization", bearer)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("password", "Finsight123"))))
                .andExpect(status().isNoContent());
        requestAndPurge(bearer);
        http.perform(get(events).cookie(operator).param("journeyId", journey.toString()))
                .andExpect(jsonPath("$.total").value(0));
        http.perform(get(events).cookie(operator).param("userId", user.toString()))
                .andExpect(jsonPath("$.total").value(3));
        http.perform(get(events).cookie(operator).param("userId", user.toString())
                        .param("type", "USER_DELETION_REQUESTED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].eventName").value("USER_DELETION_REQUESTED"))
                .andExpect(jsonPath("$.items[0].properties").isEmpty());
        http.perform(post(attempts + "/journeys").header("Authorization", bearer)
                        .contentType("application/json").content(journeyBody)).andExpect(status().isNotFound());
        assertFalse(db.sql("SELECT EXISTS(SELECT 1 FROM diagnosis_journey WHERE id=:id)")
                .param("id", journey).query(Boolean.class).single());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void experimentsEnforceRolesAndCountOnlyFirstOwnedExposureAndItsCompletion() throws Exception {
        var operator = eventSession("OPERATOR", "J".repeat(43));
        var supervisor = eventSession("SUPER_ADMIN", "K".repeat(43));
        var analyst = eventSession("ANALYST", "L".repeat(43));
        var privacy = eventSession("PRIVACY", "M".repeat(43));
        var root = "/api/v1/admin/experiments";
        var csrf = http.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isOk()).andReturn().getResponse();
        var csrfBody = json.readTree(csrf.getContentAsString());
        var csrfCookie = csrf.getCookie("__Secure-FINSIGHT_ADMIN_CSRF");
        java.util.function.UnaryOperator<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> write =
                request -> request.secure(true).cookie(csrfCookie).header("Origin", "https://localhost:18443")
                        .header(csrfBody.path("headerName").asString(), csrfBody.path("token").asString())
                        .contentType("application/json");
        var now = java.time.OffsetDateTime.now();
        var variants = java.util.List.of("SUMMARY_FIRST", "RESTART_FIRST");
        var fields = Map.of("name", "진단 안내 실험", "screenId", "B_006", "variants", variants,
                "startsAt", now.minusMinutes(1), "endsAt", now.plusHours(2));
        var body = json.writeValueAsString(fields);
        http.perform(get(root).cookie(privacy)).andExpect(status().isForbidden());
        http.perform(write.apply(post(root).cookie(analyst)).content(body)).andExpect(status().isForbidden());
        var invalid = new java.util.HashMap<String, Object>(fields);
        invalid.put("variants", java.util.List.of("SUMMARY_FIRST", "SUMMARY_FIRST"));
        http.perform(write.apply(post(root).cookie(operator)).content(json.writeValueAsString(invalid)))
                .andExpect(status().is(422));
        var draft = http.perform(write.apply(post(root).cookie(operator)).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        var id = json.readTree(draft).path("id").asString();
        var experiment = root + "/" + id;
        var edit = write.apply(patch(experiment).cookie(operator));
        http.perform(edit.content(json.writeValueAsString(Map.of("fields", fields, "expectedVersion", 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));

        var transition = experiment + "/transitions";
        var activate = json.writeValueAsString(Map.of("action", "ACTIVATE", "reason", "테스트 활성화", "expectedVersion", 1));
        http.perform(write.apply(post(transition).cookie(operator)).content(activate)).andExpect(status().isForbidden());
        http.perform(write.apply(post(transition).cookie(supervisor)).content(activate))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        http.perform(write.apply(patch(experiment).cookie(operator))
                        .content(json.writeValueAsString(Map.of("fields", fields, "expectedVersion", 2))))
                .andExpect(status().isConflict());
        var second = http.perform(write.apply(post(root).cookie(operator)).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        http.perform(write.apply(post(root + "/" + json.readTree(second).path("id").asString() + "/transitions").cookie(supervisor))
                        .content(json.writeValueAsString(Map.of("action", "ACTIVATE", "reason", "두 번째 활성화", "expectedVersion", 0))))
                .andExpect(status().isConflict());

        var owner = createAuthorization("127.0.0.63");
        var other = createAuthorization("127.0.0.64");
        var journey = UUID.randomUUID();
        var otherJourney = UUID.randomUUID();
        var app = "/api/v1/experiments";
        var attempts = "/api/v1/diagnosis/attempts";
        var assignmentBody = json.writeValueAsString(Map.of("screenId", "B_006", "journeyId", journey));
        for (var entry : Map.of(owner, journey, other, otherJourney).entrySet()) {
            http.perform(post(attempts + "/journeys").header("Authorization", entry.getKey())
                            .contentType("application/json").content(json.writeValueAsString(Map.of("journeyId", entry.getValue()))))
                    .andExpect(status().isNoContent());
            http.perform(post(app + "/assign").header("Authorization", entry.getKey()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of("screenId", "B_006", "journeyId", entry.getValue()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.experimentId").value(id));
        }
        var assignment = http.perform(post(app + "/assign").header("Authorization", owner)
                        .contentType("application/json").content(assignmentBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var variant = json.readTree(assignment).path("variantId").asString();
        http.perform(post(app + "/assign").header("Authorization", owner).contentType("application/json").content(assignmentBody))
                .andExpect(jsonPath("$.variantId").value(variant));
        var results = experiment + "/results";
        http.perform(get(results).cookie(analyst)).andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].exposed").value(0)).andExpect(jsonPath("$.variants[1].exposed").value(0));
        var exposure = app + "/" + id + "/exposure";
        var expose = json.writeValueAsString(Map.of("screenId", "B_006", "journeyId", journey, "variantId", variant));
        http.perform(post(exposure).header("Authorization", other).contentType("application/json").content(expose))
                .andExpect(status().isNotFound());
        http.perform(post(exposure).header("Authorization", owner).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("screenId", "B_006", "journeyId", journey,
                                "variantId", variants.get(1 - variants.indexOf(variant))))))
                .andExpect(status().isNotFound());
        for (int repeat = 0; repeat < 2; repeat++) {
            http.perform(post(exposure).header("Authorization", owner).contentType("application/json").content(expose))
                    .andExpect(status().isNoContent());
        }
        http.perform(get("/api/v1/admin/events").cookie(operator).param("type", "EXPERIMENT_EXPOSED")
                        .param("journeyId", journey.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].experimentId").value(id))
                .andExpect(jsonPath("$.items[0].variantId").value(variant)).andExpect(jsonPath("$.items[0].screenId").value("B_006"));
        var started = http.perform(post(attempts).header("Authorization", owner).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("journeyId", journey))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var attempt = json.readTree(started).path("id").asString();
        http.perform(post(attempts + "/" + attempt + "/submit").header("Authorization", owner).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("answers", java.util.Collections.nCopies(30, 3)))))
                .andExpect(status().isOk());
        var index = "$.variants[" + variants.indexOf(variant) + "]";
        http.perform(get(results).cookie(analyst)).andExpect(jsonPath(index + ".exposed").value(1))
                .andExpect(jsonPath(index + ".completed").value(1));
        var version = 2;
        for (var action : java.util.List.of("PAUSE", "ACTIVATE", "END")) {
            var next = Map.of("PAUSE", "PAUSED", "ACTIVATE", "ACTIVE", "END", "ENDED").get(action);
            http.perform(write.apply(post(transition).cookie(supervisor)).content(json.writeValueAsString(
                            Map.of("action", action, "reason", "테스트 상태 전환", "expectedVersion", version++))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(next));
            http.perform(post(exposure).header("Authorization", owner).contentType("application/json").content(expose))
                    .andExpect(status().is(action.equals("ACTIVATE") ? 204 : 404));
        }
        http.perform(post("/api/v1/account/reauth").header("Authorization", owner).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("password", "Finsight123")))).andExpect(status().isNoContent());
        requestAndPurge(owner);
        http.perform(get(results).cookie(analyst)).andExpect(jsonPath(index + ".exposed").value(0));
        http.perform(post(app + "/assign").header("Authorization", owner).contentType("application/json").content(assignmentBody))
                .andExpect(status().isNotFound());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void revisedGuideUsesExistingIdAndCompletionAndUnpublishesImmediately() throws Exception {
        var operator = eventSession("OPERATOR", "N".repeat(43));
        var supervisor = eventSession("SUPER_ADMIN", "O".repeat(43));
        var analyst = eventSession("ANALYST", "P".repeat(43));
        var owner = createAuthorization("127.0.0.65");
        var root = "/api/v1/admin/contents";
        var csrf = http.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isOk()).andReturn().getResponse();
        var csrfBody = json.readTree(csrf.getContentAsString());
        java.util.function.UnaryOperator<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> write =
                request -> request.secure(true).cookie(csrf.getCookie("__Secure-FINSIGHT_ADMIN_CSRF"))
                        .header("Origin", "https://localhost:18443")
                        .header(csrfBody.path("headerName").asString(), csrfBody.path("token").asString())
                        .contentType("application/json");
        http.perform(get(root).cookie(analyst).param("kind", "GUIDE").param("status", "PUBLISHED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(24));
        http.perform(get(root).cookie(analyst).param("kind", "GUIDE").param("type", "DEBT").param("status", "PUBLISHED"))
                .andExpect(jsonPath("$.length()").value(4));
        http.perform(get(root + "/page").cookie(analyst).param("kind", "GUIDE").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(24))
                .andExpect(jsonPath("$.items.length()").value(4)).andExpect(jsonPath("$.size").value(20));
        http.perform(get(root + "/page").cookie(analyst).param("kind", "GUIDE").param("size", "101"))
                .andExpect(status().is(422));
        var contentId = db.sql("SELECT id FROM service_notice WHERE external_key='DEBT-1'").query(UUID.class).single();
        http.perform(get(root + "/lookup").cookie(analyst).param("kind", "GUIDE").param("key", "DEBT-1").param("revision", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(contentId.toString()))
                .andExpect(jsonPath("$.revisionNo").value(1));
        http.perform(get(root + "/lookup").cookie(analyst).param("kind", "INSIGHT").param("key", "DEBT-1"))
                .andExpect(status().isNotFound());
        var attempt = json.readTree(http.perform(post("/api/v1/diagnosis/attempts").header("Authorization", owner))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asString();
        http.perform(post("/api/v1/diagnosis/attempts/" + attempt + "/submit").header("Authorization", owner)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("answers",
                                java.util.Collections.nCopies(30, 3)))))
                .andExpect(status().isOk());
        var detail = "/api/v1/guides/DEBT-1";
        var completion = detail + "/completion";
        http.perform(put(completion).param("attemptId", attempt).header("Authorization", owner)
                        .contentType("application/json").content("{\"completed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guide.version").value(1));
        var fields = Map.of("title", "가이드 개정 제목", "summary", "개정 요약", "body", "개정 본문",
                "sortOrder", 9, "types", java.util.List.of("DEBT", "FUTURE"), "guideCategory", "START",
                "todayAction", "오늘의 개정 행동", "nextAction", "첫 번째 행동\n두 번째 행동");
        var input = json.writeValueAsString(Map.of("kind", "GUIDE", "contentId", contentId, "fields", fields));
        http.perform(write.apply(post(root).cookie(analyst)).content(input)).andExpect(status().isForbidden());
        var revision = json.readTree(http.perform(write.apply(post(root).cookie(operator)).content(input))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revisionNo").value(2))
                .andReturn().getResponse().getContentAsString()).path("revisionId").asString();
        var localDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
        http.perform(get(root + "/page").cookie(analyst).param("kind", "GUIDE").param("status", "DRAFT")
                        .param("type", "DEBT").param("q", "개정 제목").param("from", localDate).param("to", localDate))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].revisionNo").value(2)).andExpect(jsonPath("$.items[0].publishedRevisionNo").value(1))
                .andExpect(jsonPath("$.items[0].updatedByName").isNotEmpty());
        http.perform(get(root + "/" + contentId + "/preview").cookie(analyst).param("revisionId", revision))
                .andExpect(status().isOk()).andExpect(jsonPath("$.todayAction").value("오늘의 개정 행동"));
        http.perform(post(detail + "/view").param("attemptId", attempt).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guide.version").value(1));
        var transitions = root + "/" + contentId + "/transitions";
        long version = 0;
        for (var action : java.util.List.of("SUBMIT", "APPROVE", "PUBLISH")) {
            var actor = action.equals("SUBMIT") ? operator : supervisor;
            var transition = json.writeValueAsString(Map.of("action", action, "revisionId", revision,
                    "expectedVersion", version, "serviceOnlyConfirmed", true));
            http.perform(write.apply(post(transitions).cookie(actor)).header("Idempotency-Key", "guide-" + action)
                            .content(transition)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(++version));
        }
        http.perform(post(detail + "/view").param("attemptId", attempt).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guide.id").value("DEBT-1"))
                .andExpect(jsonPath("$.guide.version").value(2)).andExpect(jsonPath("$.guide.body").value("개정 본문"))
                .andExpect(jsonPath("$.guide.steps[1]").value("두 번째 행동")).andExpect(jsonPath("$.completed").value(true));
        var event = UUID.randomUUID();
        var view = "/api/v1/diagnosis/attempts/" + attempt + "/views";
        http.perform(post(view).header("Authorization", owner).contentType("application/json").content(
                        json.writeValueAsString(Map.of("eventId", event, "contentId", "DEBT-1", "contentRevision", 1))))
                .andExpect(status().isNotFound());
        http.perform(post(view).header("Authorization", owner).contentType("application/json").content(
                        json.writeValueAsString(Map.of("eventId", event, "contentId", "DEBT-1", "contentRevision", 2))))
                .andExpect(status().isNoContent());
        assertEquals(2, db.sql("SELECT content_revision FROM app_event WHERE event_id=:event")
                .param("event", event).query(Integer.class).single());
        var history = db.sql("SELECT content_version FROM guide_activity WHERE content_id='DEBT-1' AND user_id="
                + "(SELECT user_id FROM survey_attempt WHERE id=:attempt)")
                .param("attempt", UUID.fromString(attempt)).query(Integer.class).single();
        assertEquals(1, history);
        http.perform(put(completion).param("attemptId", attempt).header("Authorization", owner)
                        .contentType("application/json").content(json.writeValueAsString(
                                Map.of("completed", false, "contentRevision", 1))))
                .andExpect(status().isConflict());
        for (var value : java.util.List.of(false, true, true)) {
            http.perform(put(completion).param("attemptId", attempt).header("Authorization", owner)
                            .contentType("application/json").content(json.writeValueAsString(Map.of("completed", value))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(value));
        }
        assertEquals(2, db.sql("""
                SELECT count(*) FROM app_event WHERE event_name='GUIDE_COMPLETION_SET' AND content_revision=2
                AND content_id='DEBT-1' AND user_id=(SELECT user_id FROM survey_attempt WHERE id=:attempt)
                """).param("attempt", UUID.fromString(attempt)).query(Integer.class).single());
        http.perform(write.apply(post(transitions).cookie(supervisor)).header("Idempotency-Key", "guide-unpublish")
                        .content(json.writeValueAsString(Map.of("action", "UNPUBLISH", "revisionId", revision,
                                "expectedVersion", version, "reason", "테스트에서 가이드 공개를 종료합니다."))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNPUBLISHED"));
        http.perform(post(detail + "/view").param("attemptId", attempt).header("Authorization", owner))
                .andExpect(status().isNotFound());
        http.perform(get("/api/v1/guides").param("attemptId", attempt).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guides.length()").value(3));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void newsMediaValidatesBytesAndOnlyPublishedEligibleArticlesExposeImages() throws Exception {
        var operator = eventSession("OPERATOR", "T".repeat(43));
        var another = eventSession("OPERATOR", "U".repeat(43));
        var supervisor = eventSession("SUPER_ADMIN", "V".repeat(43));
        var csrf = http.perform(get("/api/v1/admin/auth/csrf").secure(true))
                .andExpect(status().isOk()).andReturn().getResponse();
        var csrfBody = json.readTree(csrf.getContentAsString());
        java.util.function.UnaryOperator<org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?>> write =
                request -> request.secure(true).cookie(csrf.getCookie("__Secure-FINSIGHT_ADMIN_CSRF"))
                        .header("Origin", "https://localhost:18443")
                        .header(csrfBody.path("headerName").asString(), csrfBody.path("token").asString());
        var mediaPath = "/api/v1/admin/media";
        var newsPath = "/api/v1/admin/news";
        var png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAIXRFWHRDb21tZW50AEdQUyBURVNUIHByaXZhdGUgbWV0YWRhdGEWdQFqAAAAEklEQVR4nGOM2qfDwMDAxAAGAA+IAUiQgQ3tAAAAAElFTkSuQmCC");
        var webp = java.util.Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAUAAEAdQnkrXqGOBiOh/AAA=");
        var malformed = "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var jpeg = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        var oversized = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4097, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", oversized);
        java.util.function.BiFunction<String, byte[], org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder>
                upload = (mime, bytes) -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(mediaPath)
                    .file(new org.springframework.mock.web.MockMultipartFile("file", "fixture.img", mime, bytes));
        http.perform(write.apply(upload.apply("image/png", png).cookie(supervisor))).andExpect(status().isForbidden());
        http.perform(write.apply(upload.apply("image/png", malformed).cookie(operator))).andExpect(status().is(422));
        http.perform(write.apply(upload.apply("image/jpeg", png).cookie(operator))).andExpect(status().is(422));
        http.perform(write.apply(upload.apply("image/png", oversized.toByteArray()).cookie(operator))).andExpect(status().is(422));
        http.perform(write.apply(upload.apply("image/png", new byte[10 * 1024 * 1024 + 1]).cookie(operator))).andExpect(status().is(413));
        http.perform(write.apply(upload.apply("image/jpeg", java.util.Arrays.copyOf(jpeg.toByteArray(), 30)).cookie(operator)))
                .andExpect(status().is(422));
        http.perform(write.apply(upload.apply("image/jpeg", jpeg.toByteArray()).cookie(operator)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mime").value("image/jpeg"));
        var webpUpload = json.readTree(http.perform(write.apply(upload.apply("image/webp", webp).cookie(operator)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.width").value(2)).andExpect(jsonPath("$.mime").value("image/png"))
                .andReturn().getResponse().getContentAsString());
        var expires = UUID.fromString(webpUpload.path("mediaId").asString());
        var result = json.readTree(http.perform(write.apply(upload.apply("image/png", png).cookie(operator)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY"))
                .andReturn().getResponse().getContentAsString());
        var asset = UUID.fromString(result.path("mediaId").asString());
        var assetPath = mediaPath + "/" + asset;
        http.perform(get(assetPath).cookie(another)).andExpect(status().isNotFound());
        http.perform(get(assetPath + "/file").cookie(supervisor)).andExpect(status().isNotFound());
        var clean = http.perform(get(assetPath + "/file").cookie(operator)).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertFalse(new String(clean, java.nio.charset.StandardCharsets.ISO_8859_1).contains("GPS TEST private metadata"));
        assertEquals(2, javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(clean)).getWidth());

        var fields = Map.of("sourceUrl", "https://www.hankyung.com/article/900002", "title", "뉴스 이미지 테스트",
                "summary", "뉴스 요약", "body", "뉴스 본문", "reason", "관련 이유", "action", "작은 행동",
                "articlePublishedAt", java.time.OffsetDateTime.now().minusHours(1), "types", java.util.List.of("DEBT"), "mediaId", asset);
        var draftBody = json.writeValueAsString(Map.of("fields", fields));
        http.perform(write.apply(post(newsPath).cookie(another)).contentType("application/json").content(draftBody))
                .andExpect(status().is(422));
        var article = json.readTree(http.perform(write.apply(post(newsPath).cookie(operator))
                        .contentType("application/json").content(draftBody)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaId").value(asset.toString())).andReturn().getResponse().getContentAsString());
        var articleId = article.path("id").asString();
        http.perform(get(newsPath + "/page").cookie(operator).param("status", "DRAFT").param("q", "이미지 테스트")
                        .param("type", "DEBT").param("authorId", article.path("authorId").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(articleId)).andExpect(jsonPath("$.items[0].updatedByName").isNotEmpty());
        http.perform(get(newsPath + "/" + articleId).cookie(operator).param("revision", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(articleId));
        http.perform(get(newsPath + "/" + articleId + "/revisions").cookie(operator))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        http.perform(get(assetPath + "/file").cookie(supervisor)).andExpect(status().isOk());
        var bearer = createAuthorization("127.0.0.66");
        var edition = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
        var image = "/api/v1/news/" + articleId + "/image";
        var appImage = get(image).param("revision", "1").param("edition", edition).header("Authorization", bearer);
        http.perform(appImage).andExpect(status().isNotFound());
        var transitions = newsPath + "/" + articleId + "/1/transitions";
        int version = 0;
        for (var action : java.util.List.of("SUBMIT", "APPROVE", "PUBLISH")) {
            var actor = action.equals("SUBMIT") ? operator : supervisor;
            if (action.equals("PUBLISH")) {
                http.perform(write.apply(post(transitions).cookie(actor)).header("Idempotency-Key", "media-" + action)
                                .contentType("application/json").content(json.writeValueAsString(
                                        Map.of("action", action, "expectedVersion", version))))
                        .andExpect(status().isUnprocessableEntity());
            }
            http.perform(write.apply(post(transitions).cookie(actor)).header("Idempotency-Key", "media-" + action)
                            .contentType("application/json").content(json.writeValueAsString(
                                    Map.of("action", action, "expectedVersion", version++, "previewConfirmed", true))))
                    .andExpect(status().isOk());
        }
        http.perform(appImage).andExpect(status().isNotFound());
        var editions = "/api/v1/admin/news-editions";
        var editionVersion = news.editionOptions(java.time.LocalDate.parse(edition),
                kr.finsight.diagnosis.YfsiScoring.Pressure.DEBT).version();
        http.perform(write.apply(put(editions + "/" + edition + "/DEBT").cookie(supervisor))
                        .contentType("application/json").content(json.writeValueAsString(Map.of("slots",
                                java.util.List.of(Map.of("slot", 1, "contentId", articleId, "revisionId", 1)),
                                "expectedVersion", editionVersion))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(editionVersion + 1));
        http.perform(get(editions).cookie(supervisor).param("date", edition).param("type", "DEBT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slots[0].contentId").value(articleId))
                .andExpect(jsonPath("$.slots[0].revisionId").value(1));
        http.perform(appImage).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(clean));
        http.perform(get(image).param("revision", "2").param("edition", edition).header("Authorization", bearer))
                .andExpect(status().isNotFound());
        db.sql("UPDATE media_asset SET expires_at=statement_timestamp()-interval '1 minute' WHERE id IN (:linked,:unlinked)")
                .param("linked", asset).param("unlinked", expires).update();
        http.perform(get(mediaPath + "/" + expires).cookie(operator)).andExpect(status().isNotFound());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(media, "purge");
        assertEquals(0L, db.sql("SELECT count(*) FROM media_asset WHERE id=:id").param("id", expires).query(Long.class).single());
        http.perform(appImage).andExpect(status().isOk());
        http.perform(write.apply(post(transitions).cookie(supervisor)).header("Idempotency-Key", "media-unpublish")
                        .contentType("application/json").content(json.writeValueAsString(Map.of("action", "UNPUBLISH",
                                "expectedVersion", version, "reason", "테스트에서 기사 공개를 종료합니다."))))
                .andExpect(status().isOk());
        http.perform(appImage).andExpect(status().isNotFound());
    }

    private jakarta.servlet.http.Cookie eventSession(String role, String token) throws Exception {
        var id = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'fixture','fixture',decode('00','hex'),'ACTIVE')
                """).param("id", id).param("email", id + "@example.test").update();
        db.sql("INSERT INTO admin_role VALUES (:id,:role)").param("id", id).param("role", role).update();
        var hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.sql("INSERT INTO admin_session VALUES (:hash,:id,now(),now(),now(),now()+interval '1 hour',0)")
                .param("hash", hash).param("id", id).update();
        return new jakarta.servlet.http.Cookie("__Secure-FINSIGHT_ADMIN", token);
    }

    @Test
    void renderedResultEventsRequireAnOwnedCompletedAttemptAndDeduplicate() throws Exception {
        var auth = createAuthorization("127.0.0.52");
        var other = createAuthorization("127.0.0.53");
        var attempts = "/api/v1/diagnosis/attempts";
        var attempt = json.readTree(http.perform(post(attempts).header("Authorization", auth))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asString();
        var event = UUID.randomUUID();
        var viewPath = attempts + "/" + attempt + "/views";
        var view = json.writeValueAsString(Map.of("eventId", event));
        http.perform(post(viewPath).header("Authorization", auth).contentType("application/json").content(view))
                .andExpect(status().isNotFound());
        http.perform(post(attempts + "/" + attempt + "/submit").header("Authorization", auth)
                        .contentType("application/json").content(json.writeValueAsString(
                                Map.of("answers", java.util.Collections.nCopies(30, 3)))))
                .andExpect(status().isOk());
        http.perform(post(viewPath).header("Authorization", other).contentType("application/json").content(view))
                .andExpect(status().isNotFound());
        for (int i = 0; i < 2; i++) {
            http.perform(post(viewPath).header("Authorization", auth).contentType("application/json").content(view))
                    .andExpect(status().isNoContent());
        }
        assertEquals(1, db.sql("SELECT count(*) FROM diagnosis_view_event WHERE id=:id")
                .param("id", event).query(Integer.class).single());
        var guides = "/api/v1/guides";
        var catalog = json.readTree(http.perform(get(guides).param("attemptId", attempt).header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var guide = catalog.path("guides").get(0).path("id").asString();
        var renderedGuide = json.writeValueAsString(Map.of("eventId", UUID.randomUUID(), "contentId", guide));
        http.perform(post(viewPath).header("Authorization", auth).contentType("application/json").content(renderedGuide))
                .andExpect(status().isNotFound());
        http.perform(post(guides + "/" + guide + "/view").param("attemptId", attempt).header("Authorization", auth))
                .andExpect(status().isOk());
        http.perform(post(viewPath).header("Authorization", auth).contentType("application/json").content(renderedGuide))
                .andExpect(status().isNoContent());
        contentEvent(guides + "/" + guide + "/events", "attemptId", attempt, auth, 1, UUID.randomUUID());
        var analyst = eventSession("ANALYST", "I".repeat(43));
        var performance = "/api/v1/admin/analytics/content-performance";
        var completion = guides + "/" + guide + "/completion";
        var baseline = json.readTree(http.perform(get(performance).cookie(analyst).param("kind", "GUIDE").param("contentId", guide))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("items").get(0).path("completions").asLong();
        for (boolean completed : java.util.List.of(true, false)) {
            http.perform(put(completion).param("attemptId", attempt).header("Authorization", auth)
                            .contentType("application/json").content(json.writeValueAsString(Map.of("completed", completed))))
                    .andExpect(status().isOk());
            http.perform(get(performance).cookie(analyst).param("kind", "GUIDE").param("contentId", guide)
                            .param("type", catalog.path("diagnosis").path("type").asString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].impressions").value(1))
                    .andExpect(jsonPath("$.items[0].views").value(1))
                    .andExpect(jsonPath("$.items[0].completions").value(baseline + (completed ? 1 : 0)));
        }
    }

    @Test
    void journeyStartIsIdempotentAndOwned() throws Exception {
        var auth = createAuthorization("127.0.0.54");
        var other = createAuthorization("127.0.0.55");
        var path = "/api/v1/diagnosis/attempts";
        var journey = UUID.randomUUID();
        var body = json.writeValueAsString(Map.of("journeyId", journey));
        http.perform(post(path + "/journeys").header("Authorization", auth)
                        .contentType("application/json").content(body)).andExpect(status().isNoContent());
        var started = http.perform(post(path).header("Authorization", auth).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        var id = json.readTree(started.getResponse().getContentAsString()).path("id").asString();
        http.perform(post(path).header("Authorization", auth).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        http.perform(post(path).header("Authorization", other).contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        var milestone = path + "/" + id + "/milestones";
        for (int i = 0; i < 2; i++) {
            http.perform(post(milestone).header("Authorization", auth).contentType("application/json")
                            .content("{\"kind\":\"Q1_VIEWED\"}")).andExpect(status().isNoContent());
        }
        assertEquals(1, db.sql("SELECT count(*) FROM diagnosis_milestone WHERE attempt_id=:id")
                .param("id", UUID.fromString(id)).query(Integer.class).single());
    }

    private String createAuthorization(String ip) {
        var email = UUID.randomUUID() + "@example.test";
        signup.send(email, ip);
        assertEquals(SignupService.Outcome.CREATED, signup.register(registration(email, mail.code)));
        return "Bearer " + appSessions.login(email, "Finsight123", ip).tokens().accessToken();
    }

    private static SignupService.Registration registration(String email, String code) {
        return new SignupService.Registration(email, code, "Finsight123",
                Map.of("age", "19_22", "situation", "EMPLOYED", "gender", "UNDISCLOSED", "housing", "WITH_FAMILY",
                        "income", "REGULAR", "debt", "NO", "expenses", "FULLY_SELF_PAID"),
                true, true, false, PolicyDocuments.PREVIEW_VERSION);
    }

    static class CapturedMail implements SignupMail, PasswordReset.Mail {
        private String code;
        private String resetLink;

        @Override
        public void deliverReset(String email, String link) {
            resetLink = link;
        }

        @Override
        public void deliver(String email, String code) {
            this.code = code;
        }
    }

    @TestConfiguration
    static class MailConfiguration {
        @Bean
        @Primary
        CapturedMail capturedMail() {
            return new CapturedMail();
        }
    }
}
