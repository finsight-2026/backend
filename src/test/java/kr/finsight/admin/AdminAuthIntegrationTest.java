package kr.finsight.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.finsight.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
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
        "finsight.admin.mfa-key=" + "edededededededededededededededededededededededededededededededed",
        "finsight.admin.note-key=" + "1212121212121212121212121212121212121212121212121212121212121212",
        "finsight.admin.pii-key=" + "3434343434343434343434343434343434343434343434343434343434343434"
})
class AdminAuthIntegrationTest {
    private static final String PATH = "/api/v1/admin/auth";
    private static final String ORIGIN = "https://localhost:18443";
    private static final String SEED = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    @Autowired AdminAuth auth;
    @Autowired AdminTotp totp;
    @Autowired JdbcClient db;
    @Autowired MockMvc http;
    @Autowired JsonMapper json;
    @Autowired AdminDashboard dashboard;
    @Autowired AdminDashboardExports dashboardExports;
    @Autowired kr.finsight.jobs.JobLeases jobLeases;
    @Autowired AdminPiiExports piiExports;
    @Autowired AdminNotes notes;
    @Autowired AdminTags tags;
    @Autowired AdminBulkTags bulkTags;
    @Autowired AdminSegments segments;
    @Autowired AdminNotices notices;
    @Autowired AdminCampaigns campaigns;
    @Autowired AdminCampaignTargetPreview campaignTargets;
    @Autowired AdminCampaignDispatch campaignDispatch;
    @Autowired AdminDiagnosisFunnel funnel;
    @Autowired NewsPublicationJob newsPublicationJob;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void dashboardUsesKstBoundariesAndOneScorePerUser() {
        var day = java.time.LocalDate.of(2026, 10, 2);
        var start = day.atStartOfDay(AdminUsers.KST).toOffsetDateTime();
        var end = start.plusDays(1);
        var user = dashboardAccount(start, false, "ACTIVE");
        var other = dashboardAccount(start.minusSeconds(1), false, "ACTIVE");
        dashboardAccount(end, false, "ACTIVE");
        var fixture = dashboardAccount(start, true, "ACTIVE");
        dashboardAccount(start, false, "WITHDRAWAL_PENDING");
        var earlier = dashboardAttempt(user, start);
        var later = dashboardAttempt(user, start.plusHours(1));
        var late = dashboardAttempt(other, start.plusHours(1));
        dashboardAttempt(fixture, start.plusHours(2));
        dashboardScore(earlier, start.plusMinutes(20), "DEBT", 10, 5);
        dashboardScore(later, start.plusHours(2), "FAMILY", 100, 70);
        dashboardScore(late, end.plusHours(2), "DEBT", 30, 15);

        var result = dashboard.load(day, day, "30_PLUS", null);
        assertEquals(1, result.totals().registrations());
        assertEquals(2, result.totals().accounts());
        assertEquals(3, result.totals().attempts());
        assertEquals(2, result.totals().completedWithin24Hours());
        assertEquals(1, result.totals().diagnosedUsers());
        assertEquals(new java.math.BigDecimal("100.0"), result.totals().meanYfsi());
        assertEquals(new java.math.BigDecimal("70.0"), result.totals().meanPerceivedStress());
        assertEquals(1, result.scoreDistribution().getLast().count());
        assertEquals(1, result.typeDistribution().getLast().count());
        assertEquals(1, result.daily().getFirst().registrations());
        var filtered = dashboard.load(day, day, "30_PLUS", kr.finsight.diagnosis.YfsiScoring.Pressure.DEBT);
        assertEquals(0, filtered.totals().attempts());
        assertNull(filtered.totals().meanYfsi());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void dashboardActivityCountsUniqueUsersAndOnlyObservedCohortWeeks() {
        var today = java.time.LocalDate.now(AdminUsers.KST);
        var monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .minusWeeks(3);
        var end = monday.plusDays(13);
        var start = monday.atStartOfDay(AdminUsers.KST).toOffsetDateTime();
        var returning = dashboardAccount(start, false, "ACTIVE");
        var later = dashboardAccount(start, false, "ACTIVE");
        var test = dashboardAccount(start, true, "ACTIVE");
        assertNull(dashboard.load(monday, end, "30_PLUS", null).activity().periodActive());
        db.sql("INSERT INTO app_foreground_observation VALUES (true,:first)")
                .param("first", end.minusDays(29)).update();
        foreground(returning, monday);
        foreground(returning, monday.plusDays(7));
        foreground(returning, end);
        foreground(later, monday.plusDays(8));
        foreground(test, end);
        var summary = dashboard.load(monday, end, "30_PLUS", null);
        assertEquals(2L, summary.activity().periodActive());
        assertEquals(1L, summary.activity().dau());
        assertEquals(2L, summary.activity().mau());
        assertEquals(2L, summary.activity().d7Eligible());
        assertEquals(1L, summary.activity().d7Returned());
        assertEquals(1L, summary.cohorts().get(0).active());
        assertEquals(2L, summary.cohorts().get(1).active());
        assertNull(summary.cohorts().get(2).active());
    }

    private void foreground(UUID user, java.time.LocalDate date) {
        db.sql("INSERT INTO app_foreground_day VALUES (:user,:date)")
                .param("user", user).param("date", date).update();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void guideRateRequiresResultThenGuideOnTheSameAttempt() {
        var day = java.time.LocalDate.of(2026, 10, 3);
        var start = day.atStartOfDay(AdminUsers.KST).toOffsetDateTime();
        var user = dashboardAccount(start, false, "ACTIVE");
        var other = dashboardAccount(start, false, "ACTIVE");
        var linked = dashboardAttempt(user, start);
        var resultOnly = dashboardAttempt(other, start);
        var guideOnly = dashboardAttempt(other, start);
        for (var attempt : java.util.List.of(linked, resultOnly, guideOnly)) {
            dashboardScore(attempt, start.plusHours(1), "DEBT", 30, 20);
        }
        diagnosisView(linked, null, start.plusHours(2));
        diagnosisView(linked, "DEBT-1", start.plusHours(3));
        diagnosisView(linked, "DEBT-2", start.plusHours(4));
        diagnosisView(resultOnly, "DEBT-1", start.plusHours(2));
        diagnosisView(resultOnly, null, start.plusHours(3));
        diagnosisView(guideOnly, "DEBT-1", start.plusHours(4));
        diagnosisView(resultOnly, "DEBT-1", start.plusDays(1));

        var rate = dashboard.load(day, day, "30_PLUS", null).guideRate();
        assertEquals(2, rate.resultUsers());
        assertEquals(1, rate.guideUsers());
    }

    private void diagnosisView(UUID attempt, String content, java.time.OffsetDateTime time) {
        db.sql("""
                INSERT INTO diagnosis_view_event(id,attempt_id,content_id,recorded_at)
                VALUES (gen_random_uuid(),:attempt,:content,:time)
                """).param("attempt", attempt).param("content", content, java.sql.Types.VARCHAR)
                .param("time", time).update();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void journeyFunnelCountsEntryWithoutStartAndValidOrderedStages() {
        var day = java.time.LocalDate.of(2026, 10, 4);
        var time = day.atStartOfDay(AdminUsers.KST).toOffsetDateTime();
        var user = dashboardAccount(time, false, "ACTIVE");
        var entry = UUID.randomUUID();
        var started = UUID.randomUUID();
        for (var journey : java.util.List.of(entry, started)) {
            db.sql("INSERT INTO diagnosis_journey(id,user_id,entered_at) VALUES (:id,:user,:time)")
                    .param("id", journey).param("user", user).param("time", time).update();
        }
        var attempt = dashboardAttempt(user, time.plusMinutes(1));
        db.sql("UPDATE survey_attempt SET journey_id=:journey WHERE id=:id")
                .param("journey", started).param("id", attempt).update();
        db.sql("""
                INSERT INTO diagnosis_milestone VALUES (:id,'Q1_VIEWED',:q1),(:id,'Q15_COMPLETED',:q15)
                """).param("id", attempt).param("q1", time.plusMinutes(2)).param("q15", time.plusMinutes(3)).update();
        dashboardScore(attempt, time.plusMinutes(4), "DEBT", 30, 20);
        diagnosisView(attempt, null, time.plusMinutes(5));
        diagnosisView(attempt, "DEBT-1", time.plusDays(2));
        var result = funnel.load(day, day);
        assertEquals(2, result.entries());
        assertEquals(1, result.started());
        assertEquals(1, result.q1());
        assertEquals(1, result.q15());
        assertEquals(1, result.submitted());
        assertEquals(1, result.results());
        assertEquals(0, result.guides());
        assertEquals(0, result.provisional());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void questionStatsDeduplicateReachesAndUseOnlyCompletedAnswers() {
        var day = java.time.LocalDate.of(2026, 10, 5);
        var time = day.atStartOfDay(AdminUsers.KST).toOffsetDateTime();
        var user = dashboardAccount(time, false, "ACTIVE");
        var complete = dashboardAttempt(user, time);
        var expired = dashboardAttempt(user, time);
        var aborted = dashboardAttempt(user, time);
        for (var id : java.util.List.of(complete, expired, aborted)) {
            db.sql("UPDATE survey_attempt SET survey_version=:version WHERE id=:id")
                    .param("version", kr.finsight.diagnosis.SurveyCatalog.SURVEY_VERSION).param("id", id).update();
            questionEvent(id, 1, "VIEWED", null, time.plusMinutes(1));
        }
        questionEvent(complete, 1, "VIEWED", null, time.plusMinutes(2));
        questionEvent(complete, 1, "COMPLETED", 2000, time.plusMinutes(3));
        questionEvent(complete, 1, "COMPLETED", 1000, time.plusMinutes(4));
        questionEvent(expired, 2, "VIEWED", null, time.plusMinutes(5));
        db.sql("UPDATE survey_attempt SET aborted_at=:time,last_question=1 WHERE id=:id")
                .param("time", time.plusMinutes(5)).param("id", aborted).update();
        dashboardScore(complete, time.plusMinutes(6), "DEBT", 30, 20);
        db.sql("INSERT INTO diagnosis_answer SELECT id,1,5 FROM diagnosis WHERE attempt_id=:attempt")
                .param("attempt", complete).update();
        var questions = funnel.questions(day, day);
        assertEquals(30, questions.size());
        var q1 = questions.getFirst();
        assertEquals(3, q1.reached());
        assertEquals(1, q1.answered());
        assertEquals(1, q1.dropped());
        assertEquals(3000L, q1.averageActiveMs());
        assertEquals(java.util.List.of(0L,0L,0L,0L,1L), q1.distribution());
        assertEquals(1, questions.get(1).dropped());
        assertNull(questions.get(1).averageActiveMs());
    }

    private void questionEvent(UUID attempt, int question, String kind, Integer active, java.time.OffsetDateTime time) {
        db.sql("""
                INSERT INTO diagnosis_question_event(id,attempt_id,question_no,kind,active_ms,recorded_at)
                VALUES (gen_random_uuid(),:attempt,:question,:kind,:active,:time)
                """).param("attempt", attempt).param("question", question).param("kind", kind)
                .param("active", active, java.sql.Types.INTEGER).param("time", time).update();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void signupFunnelUsesOrderedSevenDayWindowAndResponseTimeNeedsThirtyQuestions() {
        var day = java.time.LocalDate.of(2026, 10, 6);
        var start = day.atStartOfDay(AdminUsers.KST).toOffsetDateTime();
        var user = dashboardAccount(start, false, "ACTIVE");
        var unready = dashboardAccount(start, false, "ACTIVE");
        for (var kind : java.util.List.of("TERMS", "PRIVACY")) {
            db.sql("""
                    INSERT INTO app_user_consent(user_id,document_kind,document_version,granted,recorded_at,method)
                    VALUES (:user,:kind,'v1',true,:time,'EMAIL_SIGNUP')
                    """).param("user", user).param("kind", kind).param("time", start.plusMinutes(1)).update();
        }
        dashboardAttempt(unready, start.plusMinutes(2));
        var first = dashboardAttempt(user, start.plusMinutes(2));
        var second = dashboardAttempt(user, start.plusMinutes(10));
        dashboardScore(first, start.plusMinutes(8), "DEBT", 30, 20);
        dashboardScore(second, start.plusMinutes(20), "DEBT", 30, 20);
        db.sql("UPDATE diagnosis SET score_snapshot=CAST(:snapshot AS jsonb) WHERE attempt_id=:attempt")
                .param("snapshot", json.writeValueAsString(kr.finsight.diagnosis.YfsiScoring.calculate(
                        java.util.Collections.nCopies(30, 5)))).param("attempt", second).update();
        for (int question = 1; question <= 30; question++) {
            questionEvent(first, question, "COMPLETED", 1000, start.plusMinutes(3));
            if (question < 30) questionEvent(second, question, "COMPLETED", 2000, start.plusMinutes(12));
        }
        diagnosisView(first, null, start.plusDays(2));
        diagnosisView(first, "DEBT-1", start.plusDays(7).plusSeconds(1));
        diagnosisView(second, "DEBT-1", start.plusDays(2));

        var summary = dashboard.load(day, day, "30_PLUS", null);
        var signup = summary.signupFunnel();
        assertEquals(2, signup.signups());
        assertEquals(1, signup.profiles());
        assertEquals(1, signup.started());
        assertEquals(1, signup.completed());
        assertEquals(1, signup.results());
        assertEquals(0, signup.guides());
        assertEquals(1, summary.responseTime().measuredAttempts());
        assertEquals(30000L, summary.responseTime().averageActiveMs());
        assertEquals(new java.math.BigDecimal("70.6"), summary.totals().meanPressure());
        assertEquals(new java.math.BigDecimal("66.7"), summary.totals().meanResources());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void notesEncryptVersionsAndEnforceAuthorAndOptimisticVersion() {
        var user = dashboardAccount(java.time.OffsetDateTime.now(), false, "ACTIVE");
        var author = noteAdmin("OPERATOR");
        var other = noteAdmin("OPERATOR");
        var supervisor = noteAdmin("SUPER_ADMIN");
        var input = new AdminNotes.Create("테스트 메모");
        var original = notes.create(author, user, input, "request-1");
        assertEquals(original.id(), notes.create(author, user, input, "request-1").id());
        assertEquals(1, notes.list(user).size());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                notes.create(author, user, new AdminNotes.Create("<b>메모</b>"), "bad"));
        assertEquals(403, assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                notes.edit(other, user, original.id(), new AdminNotes.Edit("다른 운영자", 0L))).getStatusCode().value());
        var updated = notes.edit(supervisor, user, original.id(), new AdminNotes.Edit("수정 메모", 0L));
        assertEquals(1, updated.version());
        assertEquals("수정 메모", notes.list(user).getFirst().text());
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                notes.edit(author, user, original.id(), new AdminNotes.Edit("오래된 편집", 0L))).getStatusCode().value());
        var encrypted = db.sql("SELECT text_encrypted FROM note_history WHERE note_id=:id AND version=0")
                .param("id", original.id()).query((row, n) -> row.getBytes(1)).single();
        assertFalse(java.util.Arrays.equals(input.text().getBytes(java.nio.charset.StandardCharsets.UTF_8), encrypted));
        notes.delete(author, user, original.id(), new AdminNotes.Delete(1L, "운영 메모 정리"));
        assertTrue(notes.list(user).isEmpty());
        assertEquals(2, db.sql("SELECT count(*) FROM note_history WHERE note_id=:id")
                .param("id", original.id()).query(Integer.class).single());
        assertEquals(3, db.sql("SELECT count(*) FROM audit_log WHERE target_id=:id AND target_type='OPERATOR_NOTE'")
                .param("id", original.id().toString()).query(Integer.class).single());
        db.sql("DELETE FROM app_user WHERE id=:id").param("id", user).update();
        assertEquals(0, db.sql("SELECT count(*) FROM note_history WHERE note_id=:id")
                .param("id", original.id()).query(Integer.class).single());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void tagsNormalizeNamesAndProtectUserSelectionAgainstStaleUpdates() {
        var actor = noteAdmin("OPERATOR");
        var user = dashboardAccount(java.time.OffsetDateTime.now(), false, "ACTIVE");
        var first = tags.create(actor, "  Follow Up  ");
        assertEquals("Follow Up", first.name());
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> tags.create(actor, "follow up")).getStatusCode().value());
        var renamed = tags.rename(actor, first.id(), "다시 확인", first.version());
        assertEquals(1, renamed.version());
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> tags.rename(actor, first.id(), "이전 버전", first.version())).getStatusCode().value());
        var second = tags.create(actor, "연락 예정");
        var selected = java.util.List.of(first.id());
        assertEquals(selected, tags.replace(actor, user, selected, java.util.List.of()).tagIds());
        assertEquals(selected, tags.replace(actor, user, selected, java.util.List.of()).tagIds());
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> tags.replace(actor, user, java.util.List.of(second.id()), java.util.List.of())).getStatusCode().value());
        assertEquals(422, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> tags.replace(actor, user, java.util.stream.IntStream.range(0, 21)
                        .mapToObj(n -> UUID.randomUUID()).toList(), selected)).getStatusCode().value());
        assertEquals(1, db.sql("SELECT count(*) FROM audit_log WHERE target_id=:id AND action='USER_TAGS_UPDATED'")
                .param("id", user.toString()).query(Integer.class).single());
        assertEquals(java.util.List.of(second.id()), tags.replace(actor, user, java.util.List.of(second.id()), selected).tagIds());
        db.sql("DELETE FROM app_user WHERE id=:id").param("id", user).update();
        assertEquals(0, db.sql("SELECT count(*) FROM user_tag WHERE user_id=:id")
                .param("id", user).query(Integer.class).single());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void bulkTagsFreezeFilterAndExcludeAccountsThatBecomeIneligible() {
        var actor = noteAdmin("OPERATOR");
        var stranger = noteAdmin("OPERATOR");
        db.sql("INSERT INTO admin_role VALUES (:id,'OPERATOR')").param("id", actor.admin().id()).update();
        var tag = tags.create(actor, "대상 확인");
        var now = java.time.OffsetDateTime.now();
        var first = dashboardAccount(now, false, "ACTIVE");
        var pending = dashboardAccount(now, false, "WITHDRAWAL_PENDING");
        var deleted = dashboardAccount(now, false, "ACTIVE");
        var limit = dashboardAccount(now, false, "ACTIVE");
        db.sql("""
                WITH inserted AS (
                    INSERT INTO tag(id,name,name_normalized,status)
                    SELECT gen_random_uuid(),'fixture '||n,'fixture '||n,'ACTIVE' FROM generate_series(1,20) n
                    RETURNING id
                )
                INSERT INTO user_tag(user_id,tag_id) SELECT :user,id FROM inserted
                """).param("user", limit).update();
        var filter = AdminUsersController.filters(null,null,null,"30_PLUS",null,null,null,null,true,1,20);
        var preview = bulkTags.preview(actor, filter, null, AdminBulkTags.Action.ADD_TAGS, java.util.List.of(tag.id()));
        assertEquals(4, preview.total());
        assertEquals(2, preview.excluded());
        assertEquals(404, assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                bulkTags.confirm(stranger, preview.previewId())).getStatusCode().value());
        var late = dashboardAccount(now, false, "ACTIVE");
        db.sql("DELETE FROM app_user WHERE id=:id").param("id", deleted).update();

        var job = bulkTags.confirm(actor, preview.previewId());
        assertEquals(job.id(), bulkTags.confirm(actor, preview.previewId()).id());
        bulkTags.run(job.id());
        bulkTags.run(job.id());
        var result = bulkTags.job(actor.admin().id(), job.id());
        assertEquals("COMPLETED", result.status());
        assertEquals(3, result.excluded());
        assertEquals(1, result.changed());
        assertEquals(java.util.List.of(tag.id()), tags.selection(first).tagIds());
        assertTrue(tags.selection(late).tagIds().isEmpty());
        assertTrue(tags.selection(pending).tagIds().isEmpty());

        var expired = bulkTags.preview(actor, null, java.util.List.of(first), AdminBulkTags.Action.REMOVE_TAGS,
                java.util.List.of(tag.id()));
        db.sql("UPDATE bulk_tag_preview SET expires_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id", expired.previewId()).update();
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                bulkTags.confirm(actor, expired.previewId())).getStatusCode().value());
        var remove = bulkTags.preview(actor, null, java.util.List.of(first), AdminBulkTags.Action.REMOVE_TAGS,
                java.util.List.of(tag.id()));
        var removeJob = bulkTags.confirm(actor, remove.previewId());
        bulkTags.run(removeJob.id());
        assertTrue(tags.selection(first).tagIds().isEmpty());
        assertEquals(1, bulkTags.job(actor.admin().id(), removeJob.id()).changed());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void piiGrantRequiresRecentMfaAndIsBoundToSessionScopeAndOwner() throws Exception {
        var actor = noteAdmin("PRIVACY");
        var admin = actor.admin().id();
        var secret = AdminTotp.decodeBase32(SEED);
        db.sql("UPDATE admin_account SET totp_secret_encrypted=:secret WHERE id=:id")
                .param("secret", totp.encrypt(secret)).param("id", admin).update();
        db.sql("INSERT INTO admin_role VALUES (:id,'SUPER_ADMIN')").param("id", admin).update();
        var rawSession = "A".repeat(43);
        var otherSession = "B".repeat(43);
        for (var raw : java.util.List.of(rawSession, otherSession)) {
            db.sql("""
                    INSERT INTO admin_session VALUES (:hash,:id,now(),now(),now()-interval '6 minutes',
                        now()+interval '1 hour',0)
                    """).param("hash", AdminAuth.hash(raw)).param("id", admin).update();
        }
        var session = new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, rawSession);
        var second = new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, otherSession);
        var now = java.time.OffsetDateTime.now();
        var user = dashboardAccount(now, false, "ACTIVE");
        var other = dashboardAccount(now, false, "ACTIVE");
        var attempt = dashboardAttempt(user, now);
        dashboardScore(attempt, now, "DEBT", 30, 20);
        var diagnosis = db.sql("SELECT id FROM diagnosis WHERE attempt_id=:id").param("id", attempt).query(UUID.class).single();
        db.sql("INSERT INTO diagnosis_answer VALUES (:id,1,4)").param("id", diagnosis).update();
        var csrf = http.perform(get(PATH + "/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        var csrfBody = json.readTree(csrf.getResponse().getContentAsString());
        var csrfCookie = csrf.getResponse().getCookie("__Secure-FINSIGHT_ADMIN_CSRF");
        var header = csrfBody.get("headerName").asString();
        var token = csrfBody.get("token").asString();
        var root = "/api/v1/admin/users/" + user;
        java.util.function.Function<String, MockHttpServletRequestBuilder> write = endpoint ->
                post(endpoint).secure(true).header("Origin", ORIGIN).cookie(csrfCookie, session)
                        .header(header, token).contentType("application/json");
        var reason = "문의 대응을 위한 개인정보 확인";
        var profileOnly = Map.of("reason", reason, "scope", java.util.List.of("PROFILE"));
        postJson(write.apply(root + "/pii-grants"), profileOnly).andExpect(status().isForbidden());
        db.sql("INSERT INTO admin_role VALUES (:id,'PRIVACY')").param("id", admin).update();
        postJson(write.apply(root + "/pii-grants"), profileOnly).andExpect(status().isPreconditionRequired());
        var code = AdminTotp.codeAt(secret, Instant.now().getEpochSecond()/30);
        postJson(write.apply(PATH + "/reauth"), Map.of("code", code)).andExpect(status().isNoContent());
        postJson(write.apply(PATH + "/reauth"), Map.of("code", code)).andExpect(status().isUnprocessableEntity());
        var grant = read(postJson(write.apply(root + "/pii-grants"), profileOnly).andExpect(status().isCreated()), "grantId");

        http.perform(get(root + "/pii").cookie(session).param("grantId", grant))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.profile.email").value(user + "@example.test"));
        http.perform(get(root + "/pii").cookie(session).param("grantId", grant).param("diagnosisId", diagnosis.toString()))
                .andExpect(status().isForbidden());
        http.perform(get(root + "/pii").cookie(second).param("grantId", grant)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/admin/users/" + other + "/pii").cookie(session).param("grantId", grant))
                .andExpect(status().isForbidden());
        var answers = read(postJson(write.apply(root + "/pii-grants"),
                Map.of("reason", reason, "scope", java.util.List.of("ANSWERS"))).andExpect(status().isCreated()), "grantId");
        http.perform(get(root + "/pii").cookie(session).param("grantId", answers).param("diagnosisId", diagnosis.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile").doesNotExist())
                .andExpect(jsonPath("$.answers[0].response").value(4));
        var encrypted = db.sql("SELECT reason_encrypted FROM pii_grant WHERE id=:id")
                .param("id", UUID.fromString(grant)).query((row, n) -> row.getBytes(1)).single();
        assertFalse(java.util.Arrays.equals(reason.getBytes(java.nio.charset.StandardCharsets.UTF_8), encrypted));
        assertEquals(2, db.sql("SELECT count(*) FROM audit_log WHERE target_id=:id AND action='PII_VIEWED'")
                .param("id", user.toString()).query(Integer.class).single());
        db.sql("UPDATE pii_grant SET expires_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id", UUID.fromString(grant)).update();
        http.perform(get(root + "/pii").cookie(session).param("grantId", grant)).andExpect(status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(root + "/pii-grants/" + answers)
                .secure(true).header("Origin", ORIGIN).cookie(csrfCookie, session).header(header, token))
                .andExpect(status().isNoContent());
        http.perform(get(root + "/pii").cookie(session).param("grantId", answers).param("diagnosisId", diagnosis.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void segmentConditionsCombineAndPreserveRevisionsWithCountOnlyPreview() {
        var actor = noteAdmin("OPERATOR");
        var today = java.time.LocalDate.now(AdminUsers.KST);
        var start = today.atStartOfDay(AdminUsers.KST).toOffsetDateTime();
        var first = tags.create(actor, "분류 하나");
        var second = tags.create(actor, "분류 둘");
        var matched = dashboardAccount(start, false, "ACTIVE");
        var optedOut = dashboardAccount(start, false, "ACTIVE");
        var pending = dashboardAccount(start, false, "WITHDRAWAL_PENDING");
        var capped = dashboardAccount(start, false, "ACTIVE");
        var withoutTag = dashboardAccount(start, false, "ACTIVE");
        for (var user : java.util.List.of(matched, optedOut, pending, capped, withoutTag)) {
            var attempt = dashboardAttempt(user, start.plusMinutes(1));
            dashboardScore(attempt, start.plusMinutes(2), user.equals(optedOut) ? "INCOME" : "DEBT", 30, 20);
            foreground(user, today);
            if (!user.equals(optedOut)) db.sql("INSERT INTO app_notification_preference(user_id,enabled) VALUES (:id,true)")
                    .param("id", user).update();
            if (!user.equals(withoutTag)) db.sql("INSERT INTO user_tag VALUES (:user,:tag)").param("user", user)
                    .param("tag", user.equals(optedOut) ? second.id() : first.id()).update();
        }
        db.sql("""
                INSERT INTO app_notification_local_slot(notification_id,user_id,delivery_day,category)
                VALUES (gen_random_uuid(),:user,:day,'REMINDER')
                """).param("user", capped).param("day", today).update();

        var range = new AdminSegments.Range(today, today);
        var condition = new AdminSegments.Conditions(java.util.List.of(
                kr.finsight.diagnosis.YfsiScoring.Pressure.DEBT, kr.finsight.diagnosis.YfsiScoring.Pressure.INCOME),
                java.util.List.of("30_PLUS"), 1, 1, range, range, range, java.util.List.of(first.id(), second.id()));
        var original = segments.create(actor, new AdminSegments.Fields("테스트 세그먼트", "", condition));
        var preview = segments.preview(original.id());
        assertEquals(4, preview.total());
        assertEquals(3, preview.consented());
        assertEquals(1, preview.localEligible());
        assertEquals(0, preview.deviceCount());
        assertEquals(0, preview.eligible());
        assertEquals(Map.of("WITHDRAWAL_PENDING", 1L, "OPTED_OUT", 1L, "DAILY_CAP", 1L, "NO_DEVICE", 1L),
                preview.excludedByReason());
        registerCampaignDevice(matched, start.plusDays(2));
        registerCampaignDevice(matched, start.plusDays(2));
        registerCampaignDevice(capped, start.minusMinutes(1));
        registerCampaignDevice(optedOut, start.plusDays(2));
        registerCampaignDevice(pending, start.plusDays(2));
        var registered = segments.preview(original.id());
        assertEquals(2, registered.deviceCount());
        assertEquals(1, registered.eligible());
        assertEquals(0L, registered.excludedByReason().get("NO_DEVICE"));
        var changed = segments.update(actor, original.id(), new AdminSegments.Fields("변경",
                "기준 변경", new AdminSegments.Conditions(null, null, null, 0, null, null, null, java.util.List.of(first.id(), second.id()))), 0);
        assertEquals(2, changed.definitionVersion());
        assertEquals(1, changed.version());
        assertEquals(0, segments.preview(changed.id()).total());
        var saved = db.sql("SELECT conditions::text FROM segment_revision WHERE segment_id=:id AND revision_no=1")
                .param("id", original.id()).query(String.class).single();
        assertEquals(2, json.readTree(saved).path("tagIds").size());
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> segments.archive(actor, original.id(), 0)).getStatusCode().value());
        segments.archive(actor, original.id(), 1);
        assertEquals("ARCHIVED", segments.list().stream().filter(row -> row.id().equals(original.id())).findFirst().orElseThrow().status());
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> segments.preview(original.id())).getStatusCode().value());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void serviceNoticesPublishOnlyApprovedVersionAndRetainOriginalDuringDraft() {
        var author = noteAdmin("OPERATOR");
        var reviewer = noteAdmin("SUPER_ADMIN");
        db.sql("INSERT INTO admin_role VALUES (:id,'SUPER_ADMIN')").param("id", reviewer.admin().id()).update();
        var fields = new AdminNotices.Fields("서비스 점검 안내", "이용 가능 시간을 안내합니다.", "정기 점검이 진행됩니다.", 1);
        assertEquals(422, assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                notices.draft(author, null, null, new AdminNotices.Fields("테스트", "요약", "<script>", 0)))
                .getStatusCode().value());
        var draft = notices.draft(author, null, null, fields);
        assertTrue(notices.list(AdminNotices.Status.PUBLISHED, fields.title()).isEmpty());
        assertEquals(fields.body(), notices.preview(draft.id(), draft.revisionId()).body());
        var review = noticeTransition(author, draft, AdminNotices.Action.SUBMIT, null, null, false);
        var approved = noticeTransition(reviewer, review, AdminNotices.Action.APPROVE, null, null, false);
        assertEquals(422, assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                noticeTransition(reviewer, approved, AdminNotices.Action.PUBLISH, null, null, false))
                .getStatusCode().value());
        var published = noticeTransition(reviewer, approved, AdminNotices.Action.PUBLISH, null, null, true);
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> noticeTransition(reviewer, published, AdminNotices.Action.ARCHIVE, null, null, false))
                .getStatusCode().value());
        assertEquals(published.revisionId(),
                noticeTransition(reviewer, approved, AdminNotices.Action.PUBLISH, null, null, true).revisionId());
        var revised = notices.draft(author, draft.id(), draft.revisionId(), null);
        assertEquals(2, revised.revisionNo());
        assertEquals(fields.body(), revised.body());
        assertEquals(published.revisionId(), notices.list(AdminNotices.Status.PUBLISHED, fields.title()).getFirst().revisionId());

        var nextReview = noticeTransition(author, revised, AdminNotices.Action.SUBMIT, null, null, false);
        var nextApproved = noticeTransition(reviewer, nextReview, AdminNotices.Action.APPROVE, null, null, false);
        var now = java.time.OffsetDateTime.now();
        var scheduled = noticeTransition(reviewer, nextApproved, AdminNotices.Action.SCHEDULE, now.plusMinutes(5),
                now.plusHours(1), true);
        notices.process(scheduled.id(), scheduled.revisionId());
        assertEquals(published.revisionId(), notices.list(AdminNotices.Status.PUBLISHED, fields.title()).getFirst().revisionId());
        db.sql("UPDATE service_notice_revision SET starts_at=clock_timestamp()-interval '1 minute' WHERE id=:id")
                .param("id", scheduled.revisionId()).update();
        notices.process(scheduled.id(), scheduled.revisionId());
        notices.process(scheduled.id(), scheduled.revisionId());
        assertEquals(scheduled.revisionId(), notices.list(AdminNotices.Status.PUBLISHED, fields.title()).getFirst().revisionId());
        assertEquals(3, db.sql("SELECT count(*) FROM service_notice_publication_event WHERE notice_id=:id")
                .param("id", draft.id()).query(Integer.class).single());
        db.sql("UPDATE service_notice_revision SET ends_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id", scheduled.revisionId()).update();
        assertTrue(notices.list(AdminNotices.Status.PUBLISHED, fields.title()).isEmpty());
        notices.process(scheduled.id(), scheduled.revisionId());
        var expired = notices.latest(scheduled.id());
        assertEquals(AdminNotices.Status.EXPIRED, expired.status());
        assertEquals(403, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> noticeTransition(author, expired, AdminNotices.Action.ARCHIVE, null, null, false))
                .getStatusCode().value());
        var archived = noticeTransition(reviewer, expired, AdminNotices.Action.ARCHIVE, null, null, false);
        assertEquals(AdminNotices.Status.ARCHIVED, archived.status());
        assertEquals(archived, noticeTransition(reviewer, expired, AdminNotices.Action.ARCHIVE, null, null, false));
        assertEquals(archived.revisionId(), notices.list(AdminNotices.Status.ARCHIVED, fields.title()).getFirst().revisionId());
        assertTrue(notices.list(null, fields.title()).stream().noneMatch(row -> row.revisionId().equals(archived.revisionId())));
        var restored = notices.draft(author, archived.id(), archived.revisionId(), null);
        assertEquals(3, restored.revisionNo());
        assertEquals(AdminNotices.Status.DRAFT, restored.status());
        assertEquals(archived.body(), restored.body());
        assertTrue(notices.list(AdminNotices.Status.PUBLISHED, fields.title()).isEmpty());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void dueNewsJobPublishesAndWritesOneAuditRecord() {
        var actor = noteAdmin("SUPER_ADMIN");
        db.sql("INSERT INTO admin_role(admin_id,role) VALUES (:id,'SUPER_ADMIN')")
                .param("id", actor.admin().id()).update();
        var article = UUID.randomUUID();
        db.sql("INSERT INTO news_article(id,source_url) VALUES (:id,'https://www.hankyung.com/article/8754322')")
                .param("id", article).update();
        db.sql("""
                INSERT INTO news_revision(article_id,revision,author_id,status,title,summary,body,reason,action,
                    article_published_at,types,starts_at,scheduled_by)
                VALUES (:id,1,:actor,'SCHEDULED','예약 검증','요약','본문','이유','행동',clock_timestamp()-interval '1 hour',
                    ARRAY['DEBT'],clock_timestamp()-interval '1 second',:actor)
                """).param("id", article).param("actor", actor.admin().id()).update();
        newsPublicationJob.processDue();
        newsPublicationJob.processDue();
        assertEquals(1, db.sql("SELECT published_revision FROM news_article WHERE id=:id")
                .param("id", article).query(Integer.class).single());
        assertEquals(1L, db.sql("""
                SELECT count(*) FROM audit_log WHERE target_id=:id AND action='NEWS_SCHEDULE_ACTIVATED'
                    AND changes_safe->>'source'='SCHEDULER'
                """).param("id", article.toString()).query(Long.class).single());
    }

    private AdminNotices.Revision noticeTransition(AdminAuth.Identity actor, AdminNotices.Revision row,
            AdminNotices.Action action, java.time.OffsetDateTime starts, java.time.OffsetDateTime ends, boolean confirmed) {
        return notices.transition(actor, row.id(),
                new AdminNotices.Change(row.revisionId(), action, row.version(), null, starts, ends, confirmed),
                row.revisionId() + "-" + action + "-" + confirmed);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void campaignPreviewPinsConditionsAndConfirmationIsIdempotent() {
        var operator = noteAdmin("OPERATOR");
        var otherOperator = noteAdmin("OPERATOR");
        var reviewer = noteAdmin("SUPER_ADMIN");
        var now = java.time.OffsetDateTime.now();
        var user = dashboardAccount(now, false, "ACTIVE");
        db.sql("INSERT INTO app_notification_preference(user_id,enabled) VALUES (:user,true)").param("user", user).update();
        var tag = tags.create(operator, "캠페인 테스트 그룹");
        db.sql("INSERT INTO user_tag VALUES (:user,:tag)").param("user", user).param("tag", tag.id()).update();
        var conditions = new AdminSegments.Conditions(null, null, null, null, null, null, null, java.util.List.of(tag.id()));
        var segment = segments.create(operator, new AdminSegments.Fields("대상", "", conditions));
        var draft = notices.draft(operator, null, null,
                new AdminNotices.Fields("이용 안내", "서비스 점검 일정", "서비스 점검이 예정되어 있습니다.", 0));
        var scheduled = AdminCampaigns.executionTime(now.plusDays(1), null);
        var input = new AdminCampaigns.Fields("점검 캠페인", segment.id(), segment.definitionVersion(), null,
                AdminCampaigns.TEMPLATE, draft.revisionId(), scheduled);
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> campaigns.create(operator, input)).getStatusCode().value());
        var submitted = noticeTransition(operator, draft, AdminNotices.Action.SUBMIT, null, null, false);
        var approved = noticeTransition(reviewer, submitted, AdminNotices.Action.APPROVE, null, null, false);
        noticeTransition(reviewer, approved, AdminNotices.Action.PUBLISH, null, null, true);
        var campaign = campaigns.create(operator, input);
        assertEquals("DRAFT", campaign.status());
        assertEquals(AdminCampaigns.TITLE, campaign.title());
        assertEquals("B_003", campaign.destination());
        registerCampaignDevice(user, scheduled.minusSeconds(1));
        var stale = campaigns.preview(operator, campaign.id(), campaign.version());
        assertEquals(1, stale.counts().localEligible());
        assertEquals(0, stale.counts().eligible());
        assertEquals(1L, stale.counts().excludedByReason().get("NO_DEVICE"));
        assertEquals(0, db.sql("SELECT count(*) FROM campaign_job WHERE campaign_id=:id").param("id", campaign.id())
                .query(Integer.class).single());

        var updated = segments.update(operator, segment.id(), new AdminSegments.Fields("수정 대상", "",
                new AdminSegments.Conditions(null, null, 5, null, null, null, null, null)), 0);
        segments.archive(operator, segment.id(), updated.version());
        var saved = campaigns.update(operator, campaign.id(), input, campaign.version());
        assertEquals(1, saved.segmentVersion());
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> campaigns.confirm(operator, campaign.id(), stale.previewId(), campaign.version())).getStatusCode().value());
        registerCampaignDevice(user, scheduled.plusSeconds(1));
        var preview = campaigns.preview(operator, saved.id(), saved.version());
        assertEquals(1, preview.counts().localEligible());
        assertEquals(1, preview.counts().eligible());
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> campaigns.confirm(otherOperator, saved.id(), preview.previewId(), saved.version())).getStatusCode().value());
        var job = campaigns.confirm(operator, saved.id(), preview.previewId(), saved.version());
        assertEquals("SCHEDULED", job.status());
        assertEquals(job.id(), campaigns.confirm(operator, saved.id(), preview.previewId(), saved.version()).id());
        assertEquals("CANCELED", campaigns.cancel(operator, saved.id(), job.version(), "일정 변경").status());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void filteredCampaignDraftPinsUsersAndRejectsChangedTargets() {
        var operator = noteAdmin("OPERATOR");
        var reviewer = noteAdmin("SUPER_ADMIN");
        var now = java.time.OffsetDateTime.now();
        var day = now.atZoneSameInstant(AdminUsers.KST).toLocalDate();
        var first = dashboardAccount(now, false, "ACTIVE");
        var pending = dashboardAccount(now, false, "WITHDRAWAL_PENDING");
        var tag = tags.create(operator, "대상 미리보기 검증");
        for (var user : java.util.List.of(first, pending)) {
            db.sql("INSERT INTO user_tag VALUES (:user,:tag)").param("user", user).param("tag", tag.id()).update();
        }
        var filters = new AdminUsers.Filters(null, null, null, null, null, tag.id(), day, day, true, 1, 20);
        var preview = campaignTargets.preview(operator.admin().id(), filters);
        assertEquals(2, preview.total());
        assertEquals(1, preview.eligible());
        assertEquals(1, preview.excluded());
        var later = dashboardAccount(now, false, "ACTIVE");
        db.sql("INSERT INTO user_tag VALUES (:user,:tag)").param("user", later).param("tag", tag.id()).update();

        var draft = notices.draft(operator, null, null,
                new AdminNotices.Fields("이용 안내", "서비스 점검 일정", "서비스 점검이 예정되어 있습니다.", 0));
        var submitted = noticeTransition(operator, draft, AdminNotices.Action.SUBMIT, null, null, false);
        var approved = noticeTransition(reviewer, submitted, AdminNotices.Action.APPROVE, null, null, false);
        noticeTransition(reviewer, approved, AdminNotices.Action.PUBLISH, null, null, true);
        var scheduled = AdminCampaigns.executionTime(now.plusDays(1), null);
        var request = new AdminCampaignTargetPreview.Draft(preview.previewId(), "필터 대상", draft.revisionId(), scheduled);
        var receipt = campaignTargets.create(operator, request, "fixed-target");
        assertEquals(receipt, campaignTargets.create(operator, request, "fixed-target"));
        assertEquals(java.util.List.of(first), campaigns.list().stream().filter(row -> row.id().equals(receipt.id()))
                .findFirst().orElseThrow().userIds());
        assertEquals(0, db.sql("SELECT count(*) FROM campaign_job WHERE campaign_id=:id").param("id", receipt.id())
                .query(Integer.class).single());

        var changed = campaignTargets.preview(operator.admin().id(), filters);
        db.sql("UPDATE app_user SET status='WITHDRAWAL_PENDING' WHERE id=:id").param("id", first).update();
        var stale = new AdminCampaignTargetPreview.Draft(changed.previewId(), "변경된 대상", draft.revisionId(), scheduled);
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> campaignTargets.create(operator, stale, "changed-target")).getStatusCode().value());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void campaignRechecksConsentAndDoesNotDeliverTheSameNoticeTwice() {
        var creator = noteAdmin("OPERATOR");
        var operator = noteAdmin("OPERATOR");
        db.sql("INSERT INTO admin_role(admin_id,role) VALUES (:id,'OPERATOR')")
                .param("id", operator.admin().id()).update();
        var reviewer = noteAdmin("SUPER_ADMIN");
        // PostgreSQL stores timestamptz at microsecond precision.
        var now = java.time.OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var eligible = dashboardAccount(now, false, "ACTIVE");
        var optedOut = dashboardAccount(now, false, "ACTIVE");
        var capped = dashboardAccount(now, false, "ACTIVE");
        db.sql("""
                INSERT INTO app_notification_preference(user_id,enabled) VALUES (:first,true),(:second,true),(:third,true)
                """).param("first", eligible).param("second", optedOut).param("third", capped).update();
        var draft = notices.draft(creator, null, null,
                new AdminNotices.Fields("서비스 점검", "점검 안내", "서비스를 점검합니다.", 0));
        var submitted = noticeTransition(creator, draft, AdminNotices.Action.SUBMIT, null, null, false);
        var approved = noticeTransition(reviewer, submitted, AdminNotices.Action.APPROVE, null, null, false);
        noticeTransition(reviewer, approved, AdminNotices.Action.PUBLISH, null, null, true);
        var scheduled = AdminCampaigns.executionTime(now.plusDays(1), null);
        var input = new AdminCampaigns.Fields("서비스 점검", null, null, java.util.List.of(eligible, optedOut, capped),
                AdminCampaigns.TEMPLATE, draft.revisionId(), scheduled);
        var first = campaigns.create(creator, input);
        var second = campaigns.create(creator, input);
        for (var item : java.util.List.of(first, second)) {
            var preview = campaigns.preview(operator, item.id(), item.version());
            assertEquals(3, preview.counts().localEligible());
            campaigns.confirm(operator, item.id(), preview.previewId(), item.version());
        }
        db.sql("UPDATE app_notification_preference SET enabled=false WHERE user_id=:user")
                .param("user", optedOut).update();
        db.sql("""
                INSERT INTO app_notification_local_slot(notification_id,user_id,delivery_day,category)
                VALUES (:id,:user,:day,'REMINDER')
                """).param("id", UUID.randomUUID()).param("user", capped)
                .param("day", scheduled.atZoneSameInstant(AdminUsers.KST).toLocalDate()).update();
        var device = registerCampaignDevice(eligible, scheduled.plusDays(1));
        campaignDispatch.run(first.id(), scheduled);
        campaignDispatch.run(first.id(), scheduled);
        campaignDispatch.run(second.id(), scheduled);
        var firstResult = campaignDispatch.deliveries(first.id(), null);
        assertEquals("COMPLETED", campaigns.find(first.id()).status());
        assertEquals(1, firstResult.counts().localInbox());
        assertEquals(0, firstResult.counts().providerAccepted());
        assertEquals(Map.of("OPTED_OUT", 1L, "DAILY_CAP", 1L), firstResult.reasons());
        assertTrue(firstResult.items().stream().allMatch(row -> row.recipient().endsWith("***@***")));
        var secondResult = campaignDispatch.deliveries(second.id(), AdminCampaignDispatch.DeliveryStatus.EXCLUDED);
        assertEquals(0, secondResult.counts().localInbox());
        assertEquals(Map.of("DUPLICATE_NOTICE", 1L, "OPTED_OUT", 1L, "DAILY_CAP", 1L), secondResult.reasons());
        assertEquals(3, secondResult.items().size());
        var duplicateCampaign = campaigns.create(creator, input);
        var duplicatePreview = campaigns.preview(operator, duplicateCampaign.id(), duplicateCampaign.version());
        assertEquals(0, duplicatePreview.counts().localEligible());
        assertEquals(1L, duplicatePreview.counts().excludedByReason().get("DUPLICATE_NOTICE"));
        assertEquals(1, db.sql("""
                SELECT count(*) FROM app_notification n JOIN app_notification_local_slot s ON s.notification_id=n.id
                WHERE n.user_id=:user AND n.kind='NOTICE' AND n.event_key=:key AND s.category='REMINDER'
                """).param("user", eligible).param("key", draft.revisionId().toString()).query(Integer.class).single());
        assertEquals(1, db.sql("""
                SELECT count(*) FROM app_push_delivery p JOIN app_notification n ON n.id=p.notification_id
                WHERE p.session_id=:session AND n.kind='NOTICE' AND p.state='WAITING'
                """).param("session", device).query(Integer.class).single());
        var retryAt = scheduled.plusMinutes(20);
        db.sql("UPDATE app_push_delivery SET state='FAILED',attempts=4,next_at=:next WHERE session_id=:id")
                .param("id", device).param("next", retryAt.plusMinutes(1)).update();
        var unknownDevice = UUID.randomUUID();
        db.sql("""
                INSERT INTO app_push_delivery(notification_id,session_id,provider,state,next_at,expires_at,attempts)
                SELECT notification_id,:unknown,provider,'UNKNOWN',next_at,expires_at,1
                FROM app_push_delivery WHERE session_id=:device
                """).param("unknown", unknownDevice).param("device", device).update();
        assertEquals(Map.of("FAILED", 1L, "UNKNOWN", 1L), campaignDispatch.deliveries(first.id(), null).deviceCounts());
        db.sql("UPDATE app_notification_preference SET enabled=false WHERE user_id=:user")
                .param("user", eligible).update();
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> campaignDispatch.retryDevices(operator, first.id(), "retry", "운영 점검", retryAt)).getStatusCode().value());
        db.sql("UPDATE app_notification_preference SET enabled=true WHERE user_id=:user")
                .param("user", eligible).update();
        var receipt = campaignDispatch.retryDevices(operator, first.id(), "retry", "운영 점검", retryAt);
        assertEquals(1, receipt.requeued());
        assertEquals(receipt, campaignDispatch.retryDevices(operator, first.id(), "retry", "운영 점검", retryAt));
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> campaignDispatch.retryDevices(operator, first.id(), "retry", "다른 사유", retryAt)).getStatusCode().value());
        assertEquals(Map.of("UNKNOWN", 1L, "WAITING", 1L), campaignDispatch.deliveries(first.id(), null).deviceCounts());
        assertEquals(1, db.sql("""
                SELECT count(*) FROM app_push_delivery WHERE session_id=:id AND attempts=4
                    AND next_at=:next AND manual_retry_at=:manual
                """).param("id", device).param("next", retryAt.plusMinutes(1)).param("manual", retryAt)
                .query(Integer.class).single());
        db.sql("UPDATE app_push_delivery SET state='FAILED' WHERE session_id=:id").param("id", device).update();
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> campaignDispatch.retryDevices(operator, first.id(), "repeat", "다시 점검", retryAt)).getStatusCode().value());
    }

    private UUID registerCampaignDevice(UUID user, java.time.OffsetDateTime expiry) {
        var device = UUID.randomUUID();
        db.sql("INSERT INTO app_session(id,user_id,refresh_hash,expires_at) VALUES (:device,:user,:hash,:expires)")
                .param("device", device).param("user", user).param("hash", device.toString()).param("expires", expiry).update();
        db.sql("INSERT INTO app_push_registration(session_id,provider,registration_token) VALUES (:device,'LOCAL',:token)")
                .param("device", device).param("token", device.toString()).update();
        return device;
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void operatingSettingsRequireRecentMfaAndVersionAndApplyToScheduledWork() throws Exception {
        var supervisor = noteAdmin("SUPER_ADMIN");
        var operator = noteAdmin("OPERATOR");
        var session = monitorSession(supervisor);
        var operatorSession = monitorSession(operator);
        var csrf = http.perform(get(PATH + "/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        var csrfBody = json.readTree(csrf.getResponse().getContentAsString());
        var csrfCookie = csrf.getResponse().getCookie("__Secure-FINSIGHT_ADMIN_CSRF");
        var path = "/api/v1/admin/settings";
        java.util.function.Function<jakarta.servlet.http.Cookie, MockHttpServletRequestBuilder> write = cookie ->
                patch(path).secure(true).header("Origin", ORIGIN).cookie(csrfCookie, cookie)
                        .header(csrfBody.get("headerName").asString(), csrfBody.get("token").asString())
                        .contentType("application/json");
        http.perform(get(path)).andExpect(status().isUnauthorized());
        http.perform(get(path).cookie(operatorSession)).andExpect(status().isForbidden());
        var initial = http.perform(get(path).cookie(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var version = json.readTree(initial.getResponse().getContentAsString()).path("policy").path("version").asLong();
        var reason = "운영 정책과 공개 범위 변경 검증";
        var update = Map.of("expectedVersion", version, "reason", reason,
                "changes", Map.of("crmPaused", true, "automaticPushPaused", true, "comparisonMetrics", java.util.List.of("EXTERNAL_AREA_MEAN"),
                        "comparisonMinimums", java.util.List.of(Map.of("type", "FAMILY", "minimum", 35)),
                        "funnelCheck", 4, "funnelPriority", 7, "operatorId", operator.admin().id()));
        postJson(write.apply(operatorSession), update).andExpect(status().isForbidden());
        db.sql("UPDATE admin_session SET reauth_at=now()-interval '6 minutes' WHERE admin_id=:id")
                .param("id", supervisor.admin().id()).update();
        postJson(write.apply(session), update).andExpect(status().isPreconditionRequired());
        db.sql("UPDATE admin_session SET reauth_at=clock_timestamp() WHERE admin_id=:id")
                .param("id", supervisor.admin().id()).update();
        for (var invalid : java.util.List.of(
                Map.of("reminderCap", 2), Map.of("totalCap", 3), Map.of("funnelCheck", 50, "funnelPriority", 10),
                Map.of("comparisonMinimums", java.util.List.of(Map.of("type", "DEBT", "minimum", 29))),
                Map.of("comparisonMetrics", java.util.List.of("email")), Map.of("operatorId", supervisor.admin().id()))) {
            postJson(write.apply(session), Map.of("expectedVersion", version, "reason", reason, "changes", invalid))
                    .andExpect(status().isUnprocessableEntity());
        }

        var snapshot = UUID.randomUUID();
        var publication = UUID.randomUUID();
        db.sql("""
                INSERT INTO comparison_snapshot(id,type,period_start,period_end,formula_version,dataset_version,sample_n,
                    metrics,status,approved_by,approved_metrics)
                SELECT :id,type,current_date,current_date,'yfsi-v1',version,40,'{"YFSI_MEAN":{"n":40,"mean":50}}',
                    'APPROVED',:actor,ARRAY['YFSI_MEAN'] FROM comparison_dataset WHERE type='DEBT'
                """).param("id", snapshot).param("actor", supervisor.admin().id()).update();
        db.sql("""
                INSERT INTO comparison_publication(id,type,snapshot_id,allowed_metrics,starts_at,ends_at,status,approver_id)
                VALUES (:id,'DEBT',:snapshot,ARRAY['YFSI_MEAN'],now()-interval '1 hour',now()+interval '1 day','ACTIVE',:actor)
                """).param("id", publication).param("snapshot", snapshot).param("actor", supervisor.admin().id()).update();
        postJson(write.apply(session), update).andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.version").value(version + 1))
                .andExpect(jsonPath("$.policy.operatorId").value(operator.admin().id().toString()));
        assertEquals("INVALIDATED", db.sql("SELECT status FROM comparison_publication WHERE id=:id")
                .param("id", publication).query(String.class).single());
        assertEquals("IN_REVIEW", db.sql("SELECT status FROM comparison_snapshot WHERE id=:id")
                .param("id", snapshot).query(String.class).single());
        assertEquals(35, db.sql("SELECT min_sample FROM comparison_dataset WHERE type='FAMILY'").query(Integer.class).single());
        http.perform(get("/api/v1/admin/diagnosis-funnel").cookie(operatorSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.limits.check").value(4)).andExpect(jsonPath("$.limits.priority").value(7));
        postJson(write.apply(session), update).andExpect(status().isConflict());
        assertEquals(reason, db.sql("""
                SELECT changes_safe->>'reason' FROM audit_log WHERE action='OPERATING_SETTINGS_UPDATED' AND actor_id=:id
                """).param("id", supervisor.admin().id()).query(String.class).single());

        var now = java.time.OffsetDateTime.now();
        var user = dashboardAccount(now, false, "ACTIVE");
        db.sql("INSERT INTO app_notification_preference(user_id,enabled) VALUES (:user,true)").param("user", user).update();
        var notice = notices.draft(operator, null, null, new AdminNotices.Fields("공지", "운영 안내", "공지 내용", 0));
        var submitted = noticeTransition(operator, notice, AdminNotices.Action.SUBMIT, null, null, false);
        var approved = noticeTransition(supervisor, submitted, AdminNotices.Action.APPROVE, null, null, false);
        noticeTransition(supervisor, approved, AdminNotices.Action.PUBLISH, null, null, true);
        var tomorrow = AdminCampaigns.executionTime(now.plusDays(1), null);
        var campaign = campaigns.create(operator, new AdminCampaigns.Fields("예약", null, null, java.util.List.of(user),
                AdminCampaigns.TEMPLATE, notice.revisionId(), tomorrow));
        var preview = campaigns.preview(operator, campaign.id(), campaign.version());
        campaigns.confirm(operator, campaign.id(), preview.previewId(), campaign.version());
        db.sql("UPDATE campaign SET scheduled_at=:missed WHERE id=:id")
                .param("missed", now.minusMinutes(1)).param("id", campaign.id()).update();
        campaignDispatch.run(campaign.id(), tomorrow);
        assertEquals("SCHEDULED", campaigns.find(campaign.id()).status());
        var resumed = new java.util.HashMap<String, Object>();
        resumed.put("crmPaused", false);
        resumed.put("operatorId", null);
        postJson(write.apply(session), Map.of("expectedVersion", version+1, "reason", reason, "changes", resumed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policy.operatorId").isEmpty());
        campaignDispatch.run(campaign.id(), tomorrow);
        assertEquals("PAUSED_WINDOW_SKIPPED", campaignDispatch.deliveries(campaign.id(), null).errorCode());
        assertEquals(0, campaignDispatch.deliveries(campaign.id(), null).counts().localInbox());
        http.perform(get("/api/v1/admin/audit-logs").cookie(session).param("action", "OPERATING_SETTINGS_UPDATED")
                        .param("reason", "공개 범위 변경"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.total").value(2));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                db.sql("UPDATE audit_log SET result='CHANGED' WHERE action='OPERATING_SETTINGS_UPDATED'").update());
    }

    @Test
    void crmWindowUsesKstAndRejectsOutsideThirtyDays() {
        var closing = java.time.OffsetDateTime.parse("2026-11-10T20:00:00+09:00");
        var next = java.time.OffsetDateTime.parse("2026-11-11T09:00:00+09:00");
        assertEquals(next, AdminCampaigns.executionTime(closing, null));
        assertEquals(closing.minusSeconds(1), AdminCampaigns.executionTime(closing.minusSeconds(1), null));
        assertEquals(next, AdminCampaigns.executionTime(closing, next));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> AdminCampaigns.executionTime(closing, next.plusDays(30)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> AdminCampaigns.executionTime(closing, next.minusSeconds(1)));
    }

    private AdminAuth.Identity noteAdmin(String role) {
        var id = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'fixture','fixture',decode('00','hex'),'ACTIVE')
                """).param("id", id).param("email", id + "@example.test").update();
        return new AdminAuth.Identity(new AdminAuth.Admin(id, "fixture"), java.util.List.of(role));
    }

    private UUID dashboardAccount(java.time.OffsetDateTime created, boolean test, String status) {
        var user = UUID.randomUUID();
        db.sql("""
                INSERT INTO app_user(id,email,created_at,comparison_test_account,status)
                VALUES (:id,:email,:created,:test,:status)
                """).param("id", user).param("email", user + "@example.test").param("created", created)
                .param("test", test).param("status", status).update();
        db.sql("INSERT INTO app_user_profile VALUES (:user,'{\"age\":\"30_PLUS\"}')")
                .param("user", user).update();
        return user;
    }

    private UUID dashboardAttempt(UUID user, java.time.OffsetDateTime start) {
        var id = UUID.randomUUID();
        db.sql("""
                INSERT INTO survey_attempt(id,user_id,survey_version,formula_version,started_at)
                VALUES (:id,:user,'v1','v1',:start)
                """).param("id", id).param("user", user).param("start", start).update();
        return id;
    }

    private void dashboardScore(UUID attempt, java.time.OffsetDateTime time, String type, int yfsi, int perceived) {
        db.sql("""
                INSERT INTO diagnosis(id,attempt_id,completed_at,representative_type,score_snapshot)
                VALUES (gen_random_uuid(),:attempt,:time,:type,jsonb_build_object(
                    'total',jsonb_build_object('numerator',:yfsi,'denominator',1),
                    'perceivedStress',jsonb_build_object('numerator',:perceived,'denominator',1)))
                """).param("attempt", attempt).param("time", time).param("type", type)
                .param("yfsi", yfsi).param("perceived", perceived).update();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void piiExportsAreEncryptedBoundToTheGrantAndPurgedOnRevocation() throws Exception {
        var actor = noteAdmin("PRIVACY");
        var session = monitorSession(actor);
        var other = monitorSession(noteAdmin("PRIVACY"));
        var supervisor = monitorSession(noteAdmin("SUPER_ADMIN"));
        var secondToken = "C".repeat(43);
        var second = new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, secondToken);
        db.sql("INSERT INTO admin_session VALUES (:hash,:id,now(),now(),now(),now()+interval '1 hour',0)")
                .param("hash", AdminAuth.hash(secondToken)).param("id", actor.admin().id()).update();
        var now = java.time.OffsetDateTime.now();
        var user = dashboardAccount(now, false, "ACTIVE");
        var anotherUser = dashboardAccount(now, false, "ACTIVE");
        var attempt = dashboardAttempt(user, now);
        dashboardScore(attempt, now, "DEBT", 42, 18);
        var diagnosis = db.sql("SELECT id FROM diagnosis WHERE attempt_id=:id").param("id", attempt).query(UUID.class).single();
        db.sql("INSERT INTO diagnosis_answer VALUES (:id,1,4)").param("id", diagnosis).update();
        db.sql("""
                UPDATE app_user_profile SET attributes='{"situationOther":"=1+1","unlisted":"should-never-export"}'
                WHERE user_id=:id
                """).param("id", user).update();
        var csrf = http.perform(get(PATH + "/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        var csrfBody = json.readTree(csrf.getResponse().getContentAsString());
        var csrfCookie = csrf.getResponse().getCookie("__Secure-FINSIGHT_ADMIN_CSRF");
        java.util.function.BiFunction<String, jakarta.servlet.http.Cookie, MockHttpServletRequestBuilder> write = (path, cookie) ->
                post(path).secure(true).header("Origin", ORIGIN).cookie(csrfCookie, cookie)
                        .header(csrfBody.get("headerName").asString(), csrfBody.get("token").asString())
                        .contentType("application/json");
        var root = "/api/v1/admin/exports";
        var grants = "/api/v1/admin/users/" + user + "/pii-grants";
        var reason = "개인정보 열람 요청에 대한 원문 확인";
        var limited = read(postJson(write.apply(grants, session),
                Map.of("reason", reason, "scope", java.util.List.of("PROFILE"))).andExpect(status().isCreated()), "grantId");
        var grant = read(postJson(write.apply(grants, session),
                Map.of("reason", reason, "scope", java.util.List.of("PROFILE", "EXPORT"))).andExpect(status().isCreated()), "grantId");
        var filter = Map.of("userId", user, "scope", "PROFILE");
        var payload = Map.of("kind", "PII_RAW", "filter", filter, "format", "CSV", "reason", reason, "grantId", grant);
        postJson(write.apply(root, supervisor), payload).andExpect(status().isForbidden());
        postJson(write.apply(root, second), payload).andExpect(status().isForbidden());
        postJson(write.apply(root, session), Map.of("kind", "PII_RAW", "filter", filter, "format", "CSV",
                "reason", reason, "grantId", limited)).andExpect(status().isForbidden());
        db.sql("UPDATE admin_session SET reauth_at=now()-interval '6 minutes' WHERE id_hash=:hash")
                .param("hash", AdminAuth.hash(session.getValue())).update();
        postJson(write.apply(root, session), payload).andExpect(status().isPreconditionRequired());
        db.sql("UPDATE admin_session SET reauth_at=now() WHERE id_hash=:hash")
                .param("hash", AdminAuth.hash(session.getValue())).update();
        var job = read(postJson(write.apply(root, session), payload).andExpect(status().isAccepted()), "id");
        postJson(write.apply(root, session), payload).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(job));
        piiExports.process(UUID.fromString(job));
        var status = "/api/v1/admin/jobs/" + job;
        http.perform(get(status).cookie(other)).andExpect(status().isNotFound());
        http.perform(get(status).cookie(session)).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.grantId").doesNotExist());
        var issue = root + "/" + job + "/download";
        postJson(write.apply(issue, second), Map.of("grantId", grant)).andExpect(status().isForbidden());
        postJson(write.apply(issue, session), Map.of("grantId", limited)).andExpect(status().isNotFound());
        var url = read(postJson(write.apply(issue, session), Map.of("grantId", grant)).andExpect(status().isOk()), "url");
        http.perform(get(java.net.URI.create(url)).cookie(second)).andExpect(status().isForbidden());
        var bytes = http.perform(get(java.net.URI.create(url)).cookie(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsByteArray();
        var content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains(user + "@example.test"));
        assertTrue(content.contains("\"'=1+1\""));
        assertFalse(content.contains("should-never-export"));
        assertFalse(content.contains(reason));
        var encrypted = db.sql("SELECT archive_encrypted,reason_encrypted FROM admin_pii_export_source WHERE export_id=:id")
                .param("id", UUID.fromString(job)).query((row, n) -> java.util.List.of(row.getBytes(1), row.getBytes(2))).single();
        assertFalse(java.util.Arrays.equals(encrypted.getFirst(), bytes));
        assertFalse(java.util.Arrays.equals(encrypted.getLast(), reason.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.sql("UPDATE pii_grant SET expires_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id", UUID.fromString(grant)).update();
        http.perform(get(java.net.URI.create(url)).cookie(session)).andExpect(status().isForbidden());
        piiExports.expire();
        http.perform(get(status).cookie(session)).andExpect(jsonPath("$.errorCode").value("ACCESS_ENDED"));

        var answerGrant = read(postJson(write.apply(grants, session),
                Map.of("reason", reason, "scope", java.util.List.of("ANSWERS", "EXPORT"))).andExpect(status().isCreated()), "grantId");
        var answers = Map.of("kind", "PII_RAW", "format", "CSV", "reason", reason, "grantId", answerGrant,
                "filter", Map.of("userId", user, "scope", "ANSWERS", "diagnosisId", diagnosis));
        postJson(write.apply(root, session), Map.of("kind", "PII_RAW", "format", "CSV", "reason", reason, "grantId", answerGrant,
                "filter", Map.of("userId", anotherUser, "scope", "ANSWERS", "diagnosisId", diagnosis)))
                .andExpect(status().isForbidden());
        var answerJob = read(postJson(write.apply(root, session), answers).andExpect(status().isAccepted()), "id");
        piiExports.process(UUID.fromString(answerJob));
        var answerUrl = read(postJson(write.apply(root + "/" + answerJob + "/download", session), Map.of("grantId", answerGrant))
                .andExpect(status().isOk()), "url");
        var response = http.perform(get(java.net.URI.create(answerUrl)).cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("\uFEFF\"문항 번호\",\"응답값\"\r\n\"1\",\"4\"\r\n",
                new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        db.sql("DELETE FROM survey_attempt WHERE id=:id").param("id", attempt).update();
        http.perform(get(java.net.URI.create(answerUrl)).cookie(session)).andExpect(status().isNotFound());

        var revoked = read(postJson(write.apply(grants, session),
                Map.of("reason", reason, "scope", java.util.List.of("PROFILE", "EXPORT"))).andExpect(status().isCreated()), "grantId");
        var cancelJob = read(postJson(write.apply(root, session), Map.of("kind", "PII_RAW", "filter", filter,
                "format", "CSV", "reason", reason, "grantId", revoked)).andExpect(status().isAccepted()), "id");
        postJson(write.apply("/api/v1/admin/jobs/" + cancelJob + "/cancel", session),
                Map.of("kind", "PII_RAW", "reason", "요청 철회"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        assertEquals(0L, db.sql("SELECT count(*) FROM admin_pii_export_source WHERE export_id=:id")
                .param("id", UUID.fromString(cancelJob)).query(Long.class).single());
        var revokedJob = read(postJson(write.apply(root, session), Map.of("kind", "PII_RAW", "filter", filter,
                "format", "CSV", "reason", reason, "grantId", revoked)).andExpect(status().isAccepted()), "id");
        piiExports.process(UUID.fromString(revokedJob));
        var revokedUrl = read(postJson(write.apply(root + "/" + revokedJob + "/download", session), Map.of("grantId", revoked))
                .andExpect(status().isOk()), "url");
        http.perform(delete(grants + "/" + revoked).secure(true).header("Origin", ORIGIN).cookie(csrfCookie, session)
                .header(csrfBody.get("headerName").asString(), csrfBody.get("token").asString()))
                .andExpect(status().isNoContent());
        http.perform(get(java.net.URI.create(revokedUrl)).cookie(session)).andExpect(status().isNotFound());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void dashboardExportsPinTheFilterAndProtectEveryDownload() throws Exception {
        var analyst = noteAdmin("ANALYST");
        var other = noteAdmin("ANALYST");
        var supervisor = noteAdmin("SUPER_ADMIN");
        var session = monitorSession(analyst);
        var otherSession = monitorSession(other);
        var supervisorSession = monitorSession(supervisor);
        var csrf = http.perform(get(PATH + "/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        var csrfBody = json.readTree(csrf.getResponse().getContentAsString());
        var csrfCookie = csrf.getResponse().getCookie("__Secure-FINSIGHT_ADMIN_CSRF");
        java.util.function.BiFunction<String, jakarta.servlet.http.Cookie, MockHttpServletRequestBuilder> write = (path, cookie) ->
                post(path).secure(true).header("Origin", ORIGIN).cookie(csrfCookie, cookie)
                        .header(csrfBody.get("headerName").asString(), csrfBody.get("token").asString())
                        .contentType("application/json");
        var now = java.time.OffsetDateTime.now();
        var today = now.atZoneSameInstant(AdminUsers.KST).toLocalDate().toString();
        var user = dashboardAccount(now, false, "ACTIVE");
        var root = "/api/v1/admin/exports";
        var filter = Map.of("from", today, "to", today, "age", "30_PLUS");
        var request = Map.of("kind", "DASHBOARD_AGGREGATE", "filter", filter, "format", "CSV");
        var pdfRequest = Map.of("kind", "DASHBOARD_AGGREGATE", "filter", filter, "format", "PDF");
        postJson(write.apply(root, supervisorSession), request).andExpect(status().isForbidden());
        postJson(write.apply(root, session), Map.of("kind", "DASHBOARD_AGGREGATE",
                "format", "CSV", "columns", java.util.List.of("email"))).andExpect(status().isUnprocessableEntity());
        var csv = read(postJson(write.apply(root, session), request).andExpect(status().isAccepted()), "id");
        postJson(write.apply(root, session), request).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(csv));
        postJson(write.apply(root, session), pdfRequest).andExpect(status().isConflict());
        var jobs = "/api/v1/admin/jobs";
        http.perform(get(jobs + "/" + csv).cookie(otherSession)).andExpect(status().isNotFound());
        dashboardExports.process(UUID.fromString(csv));
        http.perform(get(jobs + "/" + csv).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.expiresAt").isString());
        postJson(write.apply(root + "/" + csv + "/download", otherSession), Map.of()).andExpect(status().isNotFound());
        var csvUrl = read(postJson(write.apply(root + "/" + csv + "/download", session), Map.of())
                .andExpect(status().isOk()), "url");
        http.perform(get(java.net.URI.create(csvUrl)).cookie(otherSession)).andExpect(status().isNotFound());
        http.perform(get(java.net.URI.create(csvUrl.replaceFirst("signature=.*", "signature=" + "0".repeat(64))))
                .cookie(session)).andExpect(status().isNotFound());
        var file = http.perform(get(java.net.URI.create(csvUrl)).cookie(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        var csvText = new String(file.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csvText.contains(today + " ~ " + today));
        assertTrue(csvText.contains("30_PLUS"));
        assertTrue(csvText.contains("24시간 내 완료 / 진단 시작"));
        assertTrue(csvText.contains(analyst.admin().id().toString()));
        assertFalse(csvText.contains(user.toString()));
        db.sql("UPDATE admin_dashboard_export_link SET expires_at=now()-interval '1 second' WHERE export_id=:id")
                .param("id", UUID.fromString(csv)).update();
        http.perform(get(java.net.URI.create(csvUrl)).cookie(session)).andExpect(status().isNotFound());

        var pdf = read(postJson(write.apply(root, session), pdfRequest).andExpect(status().isAccepted()), "id");
        dashboardExports.process(UUID.fromString(pdf));
        var pdfUrl = read(postJson(write.apply(root + "/" + pdf + "/download", session), Map.of())
                .andExpect(status().isOk()), "url");
        var pdfBytes = http.perform(get(java.net.URI.create(pdfUrl)).cookie(session)).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            assertTrue(document.getNumberOfPages() >= 2);
            var extracted = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertTrue(extracted.contains("FINSIGHT 대시보드 집계"));
            assertTrue(extracted.contains("가입 7일 재방문율"));
        }
        var cancellable = read(postJson(write.apply(root, session), request).andExpect(status().isAccepted()), "id");
        postJson(write.apply(jobs + "/" + cancellable + "/cancel", session), Map.of("reason", "필터 변경"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        dashboardExports.process(UUID.fromString(cancellable));
        http.perform(get(jobs + "/" + cancellable).cookie(session)).andExpect(jsonPath("$.status").value("CANCELED"));

        db.sql("""
                INSERT INTO deletion_operation(id,kind,status,completed_at)
                VALUES (gen_random_uuid(),'DATA_DELETION','COMPLETED',now())
                """).update();
        http.perform(get(java.net.URI.create(pdfUrl)).cookie(session)).andExpect(status().isNotFound());
        http.perform(get(jobs + "/" + pdf).cookie(session)).andExpect(jsonPath("$.errorCode").value("SOURCE_CHANGED"));
        var queued = read(postJson(write.apply(root, session), request).andExpect(status().isAccepted()), "id");
        db.sql("DELETE FROM admin_role WHERE admin_id=:id AND role='ANALYST'").param("id", analyst.admin().id()).update();
        http.perform(get(java.net.URI.create(pdfUrl)).cookie(session)).andExpect(status().isForbidden());
        db.sql("INSERT INTO admin_role(admin_id,role) VALUES (:id,'ANALYST')").param("id", analyst.admin().id()).update();
        http.perform(get(jobs + "/" + queued).cookie(session)).andExpect(jsonPath("$.errorCode").value("ACCESS_REVOKED"));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void aggregateExportReportsRunningAndRecoversAfterLeaseExpiry() throws Exception {
        var actor = noteAdmin("ANALYST");
        var session = monitorSession(actor);
        var input = new AdminDashboardExports.Request(AdminDashboardExports.Kind.DASHBOARD_AGGREGATE,
                null, null, AdminDashboardExports.Format.CSV);
        var job = dashboardExports.request(actor, input);
        var path = "/api/v1/admin/jobs/" + job.id();
        var abandoned = jobLeases.acquire(AdminDashboardExports.QUEUE, job.id()).orElseThrow();
        dashboardExports.process(job.id());
        http.perform(get(path).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING")).andExpect(jsonPath("$.startedAt").isString());
        http.perform(get("/api/v1/admin/jobs").param("status", "RUNNING").cookie(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(job.id().toString()));
        db.sql("UPDATE job_execution_lease SET expires_at=clock_timestamp()-interval '1 second' WHERE job_id=:id")
                .param("id", job.id()).update();
        assertFalse(jobLeases.lockOwned(abandoned));
        dashboardExports.process(job.id());
        http.perform(get(path).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.retryCount").value(0));
        assertEquals(1, db.sql("SELECT count(*) FROM admin_dashboard_export WHERE id=:id AND archive IS NOT NULL")
                .param("id", job.id()).query(Integer.class).single());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void aggregateRetryBacksOffAndRequeuesOnlyOwnedRetryableFailuresOnce() throws Exception {
        var actor = noteAdmin("ANALYST");
        var session = monitorSession(actor);
        var other = monitorSession(noteAdmin("ANALYST"));
        var csrf = http.perform(get(PATH + "/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        var csrfBody = json.readTree(csrf.getResponse().getContentAsString());
        var csrfCookie = csrf.getResponse().getCookie("__Secure-FINSIGHT_ADMIN_CSRF");
        var input = new AdminDashboardExports.Request(AdminDashboardExports.Kind.DASHBOARD_AGGREGATE,
                null, null, AdminDashboardExports.Format.CSV);
        var job = dashboardExports.request(actor, input);
        var detail = "/api/v1/admin/jobs/" + job.id();
        var intervals = java.util.List.of(60L, 300L, 900L);
        for (int attempt = 0; attempt < intervals.size(); attempt++) {
            dashboardExports.recordFailure(job.id(), attempt, true);
            long seconds = db.sql("""
                    SELECT extract(epoch FROM next_run_at-clock_timestamp())::bigint
                    FROM admin_dashboard_export WHERE id=:id
                    """).param("id", job.id()).query(Long.class).single();
            assertTrue(seconds >= intervals.get(attempt) - 2 && seconds <= intervals.get(attempt));
            dashboardExports.process(job.id());
            http.perform(get(detail).cookie(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.retryCount").value(attempt + 1))
                    .andExpect(jsonPath("$.retryable").value(false))
                    .andExpect(jsonPath("$.nextRunAt").exists());
            db.sql("UPDATE admin_dashboard_export SET next_run_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                    .param("id", job.id()).update();
        }
        dashboardExports.recordFailure(job.id(), 3, true);
        http.perform(get(detail).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.retryable").value(true));
        java.util.function.BiFunction<jakarta.servlet.http.Cookie, String, MockHttpServletRequestBuilder> retry = (cookie, key) ->
                post(detail + "/retry").secure(true).header("Origin", ORIGIN).cookie(csrfCookie, cookie)
                        .header(csrfBody.get("headerName").asString(), csrfBody.get("token").asString())
                        .header("Idempotency-Key", key).contentType("application/json");
        var reason = Map.of("reason", "내보내기 생성 오류 복구");
        postJson(retry.apply(other, "retry"), reason).andExpect(status().isNotFound());

        var queued = dashboardExports.request(actor, input);
        postJson(retry.apply(session, "retry"), reason).andExpect(status().isConflict());
        dashboardExports.cancel(actor, queued.id(), "기존 요청 사용", AdminDashboardExports.Kind.DASHBOARD_AGGREGATE);
        for (int replay = 0; replay < 2; replay++) {
            postJson(retry.apply(session, "retry"), reason).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id").value(job.id().toString())).andExpect(jsonPath("$.status").value("QUEUED"));
        }
        postJson(retry.apply(session, "retry"), Map.of("reason", "다른 사유")).andExpect(status().isConflict());
        http.perform(get(detail).cookie(session)).andExpect(jsonPath("$.retryCount").value(4));
        dashboardExports.process(job.id());
        http.perform(get(detail).cookie(session)).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.errorCode").isEmpty()).andExpect(jsonPath("$.retryable").value(false));
        var permanent = dashboardExports.request(actor, input);
        dashboardExports.recordFailure(permanent.id(), 0, false);
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> dashboardExports.retry(actor, permanent.id(), "재시도 요청", "permanent")).getStatusCode().value());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void contentPerformanceExportsMatchTheReportAndKeepFilesPrivate() throws Exception {
        var analyst = noteAdmin("ANALYST");
        var session = monitorSession(analyst);
        var other = monitorSession(noteAdmin("ANALYST"));
        var operator = monitorSession(noteAdmin("OPERATOR"));
        var csrf = http.perform(get(PATH + "/csrf").secure(true)).andExpect(status().isOk()).andReturn().getResponse();
        var csrfBody = json.readTree(csrf.getContentAsString());
        java.util.function.BiFunction<String, jakarta.servlet.http.Cookie, MockHttpServletRequestBuilder> write = (path, cookie) ->
                post(path).secure(true).header("Origin", ORIGIN).cookie(csrf.getCookie("__Secure-FINSIGHT_ADMIN_CSRF"), cookie)
                        .header(csrfBody.path("headerName").asString(), csrfBody.path("token").asString()).contentType("application/json");
        var date = java.time.LocalDate.now(AdminUsers.KST);
        var stamp = date.atStartOfDay(AdminUsers.KST).plusHours(1).toOffsetDateTime();
        var actor = dashboardAccount(stamp, false, "ACTIVE");
        var viewer = dashboardAccount(stamp, false, "ACTIVE");
        var formulaTitle = "=합계 확인 \"테스트\"";
        db.sql("UPDATE service_notice_revision SET title=:title WHERE notice_id=(SELECT id FROM service_notice WHERE external_key='DEBT-1')")
                .param("title", formulaTitle).update();
        for (var item : java.util.List.of(Map.entry(actor, "CONTENT_IMPRESSION"), Map.entry(actor, "CONTENT_VIEWED"),
                Map.entry(viewer, "CONTENT_VIEWED"))) {
            db.sql("""
                    INSERT INTO app_event(event_name,user_id,content_kind,content_id,content_revision,content_type,occurred_at,received_at)
                    VALUES (:event,:user,'GUIDE','DEBT-1',1,'DEBT',:stamp,:stamp)
                    """).param("event", item.getValue()).param("user", item.getKey()).param("stamp", stamp).update();
        }
        var endpoint = "/api/v1/admin/analytics/content-performance";
        http.perform(get(endpoint).cookie(session).param("from", date.toString()).param("to", date.toString())
                        .param("kind", "GUIDE").param("type", "DEBT").param("contentId", "DEBT-1").param("revision", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].impressions").value(1)).andExpect(jsonPath("$.items[0].views").value(2))
                .andExpect(jsonPath("$.items[0].ctrNumerator").value(1)).andExpect(jsonPath("$.items[0].ctr").value(100));
        var filter = Map.of("from", date, "to", date, "kind", "GUIDE", "type", "DEBT", "contentId", "DEBT-1", "revision", 1);
        var csvRequest = Map.of("kind", "CONTENT_PERFORMANCE", "filter", filter, "format", "CSV");
        var pdfRequest = Map.of("kind", "CONTENT_PERFORMANCE", "filter", filter, "format", "PDF");
        var exports = "/api/v1/admin/exports";
        var jobs = "/api/v1/admin/jobs";
        postJson(write.apply(exports, operator).header("Idempotency-Key", "scope"), csvRequest).andExpect(status().isForbidden());
        postJson(write.apply(exports, session), csvRequest).andExpect(status().isUnprocessableEntity());
        var csv = read(postJson(write.apply(exports, session).header("Idempotency-Key", "csv"), csvRequest)
                .andExpect(status().isAccepted()), "id");
        postJson(write.apply(exports, session).header("Idempotency-Key", "csv"), pdfRequest).andExpect(status().isConflict());
        postJson(write.apply(exports, session).header("Idempotency-Key", "other-format"), pdfRequest).andExpect(status().isConflict());
        postJson(write.apply(exports, session).header("Idempotency-Key", "other-key"), csvRequest)
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(csv));
        dashboardExports.process(UUID.fromString(csv));
        postJson(write.apply(exports, session).header("Idempotency-Key", "csv"), csvRequest)
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(csv));
        http.perform(get(jobs).cookie(session).param("kind", "CONTENT_PERFORMANCE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(csv))
                .andExpect(jsonPath("$.items[0].scope").value("CONTENT_FILTER"));
        http.perform(get(jobs + "/" + csv).cookie(other)).andExpect(status().isNotFound());
        var csvLink = read(postJson(write.apply(exports + "/" + csv + "/download", session), Map.of())
                .andExpect(status().isOk()), "url");
        http.perform(get(java.net.URI.create(csvLink)).cookie(other)).andExpect(status().isNotFound());
        var csvResult = http.perform(get(java.net.URI.create(csvLink)).cookie(session)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("finsight-content-performance-")))
                .andReturn().getResponse().getContentAsByteArray();
        var text = new String(csvResult, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFF"));
        assertTrue(text.contains(date + " ~ " + date));
        assertTrue(text.contains(analyst.admin().id().toString()));
        assertTrue(text.contains("잠정 집계"));
        assertTrue(text.contains("\"'=합계 확인 \"\"테스트\"\"\",\"1\",\"1\",\"2\",\"1\",\"1\",\"100.00\""));
        assertFalse(text.contains("DEBT-2"));
        assertFalse(text.contains(actor.toString()));

        var pdf = read(postJson(write.apply(exports, session).header("Idempotency-Key", "pdf"), pdfRequest)
                .andExpect(status().isAccepted()), "id");
        dashboardExports.process(UUID.fromString(pdf));
        var pdfLink = read(postJson(write.apply(exports + "/" + pdf + "/download", session), Map.of())
                .andExpect(status().isOk()), "url");
        var pdfResult = http.perform(get(java.net.URI.create(pdfLink)).cookie(session)).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andReturn().getResponse().getContentAsByteArray();
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdfResult)) {
            assertEquals(2, doc.getNumberOfPages());
            var extracted = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertTrue(extracted.contains("FINSIGHT 콘텐츠 성과"));
            assertTrue(extracted.contains("잠정 집계"));
            assertTrue(extracted.contains("조회율 분모"));
            assertTrue(extracted.contains(formulaTitle));
        }
        db.sql("INSERT INTO deletion_operation(id,kind,status,completed_at) VALUES (gen_random_uuid(),'DATA_DELETION','COMPLETED',now())").update();
        http.perform(get(java.net.URI.create(pdfLink)).cookie(session)).andExpect(status().isNotFound());
        http.perform(get(jobs + "/" + pdf).cookie(session)).andExpect(jsonPath("$.errorCode").value("SOURCE_CHANGED"));
    }

    private jakarta.servlet.http.Cookie monitorSession(AdminAuth.Identity admin) throws Exception {
        db.sql("INSERT INTO admin_role(admin_id,role) VALUES (:id,:role)")
                .param("id", admin.admin().id()).param("role", admin.permissions().getFirst()).update();
        var token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(admin.admin().id().toString()
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        db.sql("INSERT INTO admin_session VALUES (:hash,:id,now(),now(),now(),now()+interval '1 hour',0)")
                .param("hash", hash).param("id", admin.admin().id()).update();
        return new jakarta.servlet.http.Cookie(AdminAuthController.SESSION_COOKIE, token);
    }

    @Test
    void bootstrapMfaOriginCsrfAndSessionLifecycle() throws Exception {
        assertEquals("287082", AdminTotp.codeAt(AdminTotp.decodeBase32(SEED), 1));
        assertTrue(auth.bootstrap("owner@example.test", "LocalExample123!", SEED));
        assertFalse(auth.bootstrap("second@example.test", "LocalExample123!", SEED));
        var credentials = Map.of("email", "owner@example.test", "password", "LocalExample123!");
        http.perform(get(PATH + "/me")).andExpect(status().isUnauthorized());

        var csrf = http.perform(get(PATH + "/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        var csrfBody = json.readTree(csrf.getResponse().getContentAsString());
        var csrfCookie = csrf.getResponse().getCookie("__Secure-FINSIGHT_ADMIN_CSRF");
        assertNotNull(csrfCookie);
        var header = csrfBody.get("headerName").asString();
        var token = csrfBody.get("token").asString();
        http.perform(post(PATH + "/login").secure(true).header("Origin", ORIGIN).cookie(csrfCookie)
                .contentType("application/json").content(json.writeValueAsString(credentials)))
                .andExpect(status().isForbidden());
        http.perform(post(PATH + "/login").secure(true).header("Origin", "https://wrong.example")
                .cookie(csrfCookie).header(header, token).contentType("application/json")
                .content(json.writeValueAsString(credentials))).andExpect(status().isForbidden());

        java.util.function.Function<String, MockHttpServletRequestBuilder> write = endpoint ->
                post(PATH + endpoint).secure(true).header("Origin", ORIGIN).cookie(csrfCookie)
                        .header(header, token).contentType("application/json");
        var challenge = read(postJson(write.apply("/login"), credentials).andExpect(status().isOk()), "challengeId");
        http.perform(get(PATH + "/me")).andExpect(status().isUnauthorized());

        var step = Instant.now().getEpochSecond() / 30;
        var code = AdminTotp.codeAt(AdminTotp.decodeBase32(SEED), step);
        var mfa = postJson(write.apply("/mfa"), Map.of("challengeId", challenge, "code", code))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissions[0]").value("SUPER_ADMIN")).andReturn();
        var session = mfa.getResponse().getCookie(AdminAuthController.SESSION_COOKIE);
        assertNotNull(session);
        assertTrue(session.isHttpOnly());
        assertTrue(session.getSecure());
        assertEquals("Strict", session.getAttribute("SameSite"));
        http.perform(get(PATH + "/me").cookie(session)).andExpect(status().isOk());
        var users = "/api/v1/admin/users";
        http.perform(get(users)).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/admin/tags")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/admin/contents")).andExpect(status().isUnauthorized());
        var jobsPath = "/api/v1/admin/jobs";
        var deletionsPath = "/api/v1/admin/deletion-requests";
        http.perform(get(jobsPath)).andExpect(status().isUnauthorized());
        http.perform(get(jobsPath).cookie(session)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/admin/contents").cookie(session).param("kind", "NOTICE")).andExpect(status().isOk());
        http.perform(get("/api/v1/admin/tags").cookie(session)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/admin/segments").cookie(session)).andExpect(status().isForbidden());
        http.perform(get(users).cookie(session)).andExpect(status().isForbidden());
        db.sql("INSERT INTO admin_role SELECT id, 'ANALYST' FROM admin_account").update();
        http.perform(get("/api/v1/admin/dashboard")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/admin/dashboard").cookie(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.daily.length()").value(30))
                .andExpect(jsonPath("$.users").doesNotExist());
        http.perform(get(users).cookie(session)).andExpect(status().isForbidden());
        db.sql("INSERT INTO admin_role SELECT id, 'OPERATOR' FROM admin_account").update();
        http.perform(get("/api/v1/admin/tags").cookie(session)).andExpect(status().isOk());
        http.perform(get("/api/v1/admin/segments").cookie(session)).andExpect(status().isOk());
        var deletion = UUID.randomUUID();
        db.sql("""
                INSERT INTO deletion_operation(id,kind,status,requested_at,due_at)
                VALUES (:id,'ACCOUNT_WITHDRAWAL','SCHEDULED',now()-interval '16 days',now()-interval '1 day')
                """).param("id", deletion).update();
        var tagJob = UUID.randomUUID();
        var actor = db.sql("SELECT admin_id FROM admin_session").query(UUID.class).single();
        db.sql("""
                INSERT INTO bulk_tag_job(id,preview_id,actor_id,status,total,excluded,changed,completed_at)
                VALUES (:id,:preview,:actor,'COMPLETED',3,1,2,now())
                """).param("id", tagJob).param("preview", UUID.randomUUID()).param("actor", actor).update();
        http.perform(get(jobsPath).cookie(session).param("actorId", actor.toString()).param("kind", "BULK_TAG")
                        .param("status", "COMPLETED").param("size", "1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].id").value(tagJob.toString()))
                .andExpect(jsonPath("$.items[0].processed").value(3)).andExpect(jsonPath("$.items[0].payload").doesNotExist());
        http.perform(get(jobsPath).cookie(session).param("actorId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        http.perform(get(jobsPath + "/" + deletion).cookie(session)).andExpect(status().isNotFound());
        http.perform(get(deletionsPath).cookie(session)).andExpect(status().isForbidden());
        db.sql("INSERT INTO admin_role(admin_id,role) VALUES (:actor,'PRIVACY')").param("actor", actor).update();
        http.perform(get(deletionsPath + "/" + deletion).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true)).andExpect(jsonPath("$.stage").value("AWAITING_PURGE"))
                .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist());
        db.sql("""
                UPDATE deletion_operation SET due_at=now()+interval '30 minutes',retry_count=2,
                    next_run_at=now()+interval '5 minutes',error_code='WITHDRAWAL_PURGE_FAILED' WHERE id=:id
                """).param("id", deletion).update();
        http.perform(get(deletionsPath + "/" + deletion).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deadlineWarning").value(true))
                .andExpect(jsonPath("$.stage").value("PURGE_RETRY_WAIT"))
                .andExpect(jsonPath("$.retryCount").value(2))
                .andExpect(jsonPath("$.nextRunAt").isString())
                .andExpect(jsonPath("$.errorCode").value("WITHDRAWAL_PURGE_FAILED"));
        db.sql("""
                UPDATE deletion_operation SET status='FAILED',due_at=now()-interval '1 minute',
                    completed_at=now()-interval '2 minutes',next_run_at=NULL WHERE id=:id
                """).param("id", deletion).update();
        http.perform(get(deletionsPath + "/" + deletion).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true))
                .andExpect(jsonPath("$.stage").value("PURGE_REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(false));
        http.perform(get(deletionsPath).cookie(session).param("from", "2000-01-01").param("to", "2000-01-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        db.sql("DELETE FROM admin_role WHERE admin_id=:actor AND role='PRIVACY'").param("actor", actor).update();
        var user = UUID.randomUUID();
        var unrelated = UUID.randomUUID();
        var attempt = UUID.randomUUID();
        var diagnosis = UUID.randomUUID();
        var tag = UUID.randomUUID();
        db.sql("INSERT INTO app_user (id,email,status) VALUES (:id,'person@example.test','ACTIVE'),"
                + "(:other,'second@example.test','ACTIVE')").param("id", user).param("other", unrelated).update();
        db.sql("""
                INSERT INTO app_user_profile VALUES (:id, '{"age":"19_22","situation":"OTHER","situationOther":"private input"}')
                """).param("id", user).update();
        db.sql("INSERT INTO survey_attempt (id,user_id,survey_version,formula_version,started_at)"
                + " VALUES (:id,:user,'survey-v1','formula-v1',now())").param("id", attempt).param("user", user).update();
        db.sql("INSERT INTO diagnosis VALUES (:attempt,:id,now(),'DEBT','{}')")
                .param("attempt", attempt).param("id", diagnosis).update();
        db.sql("INSERT INTO tag (id,name,name_normalized,status) VALUES (:id,'테스트','테스트','ACTIVE')")
                .param("id", tag).update();
        db.sql("INSERT INTO user_tag VALUES (:user,:tag)").param("user", user).param("tag", tag).update();
        http.perform(get(users).cookie(session).param("diagnosisId", diagnosis.toString())
                        .param("age", "19_22").param("tagId", tag.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].maskedEmail").value("p***@***"))
                .andExpect(jsonPath("$.items[0].tags[0]").value("테스트"));
        http.perform(get(users).cookie(session).param("includeNoDiagnosis", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        http.perform(get(users + "/" + user).cookie(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile.situationOther").doesNotExist())
                .andExpect(jsonPath("$.recentDiagnoses[0].id").value(diagnosis.toString()));
        http.perform(get(users + "/" + unrelated + "/diagnoses/" + diagnosis).cookie(session))
                .andExpect(status().isNotFound());
        http.perform(get(users + "/" + user + "/diagnoses/" + diagnosis).cookie(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.formulaVersion").value("formula-v1"))
                .andExpect(jsonPath("$.answers").doesNotExist());
        var snapshot = UUID.randomUUID();
        db.sql("UPDATE comparison_dataset SET min_sample = 30 WHERE type = 'FAMILY'").update();
        db.sql("""
                INSERT INTO comparison_snapshot
                    (id,type,period_start,period_end,formula_version,dataset_version,sample_n,metrics,status,created_by)
                SELECT :id,c.type,current_date,current_date,'yfsi-v1',c.version,30,
                    '{"YFSI_MEAN":{"n":30,"mean":50}}','IN_REVIEW',a.id
                FROM comparison_dataset c CROSS JOIN admin_account a WHERE c.type = 'FAMILY'
                """).param("id", snapshot).update();
        var transition = "/api/v1/admin/comparison-snapshots/" + snapshot + "/transitions";
        var preview = "/api/v1/admin/comparison-snapshots/" + snapshot + "/preview";
        http.perform(get(preview)).andExpect(status().isUnauthorized());
        http.perform(get(preview).cookie(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.YFSI_MEAN").value(50));
        var clock = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        var approval = Map.of("action", "APPROVE", "expectedVersion", 0,
                "startsAt", clock.minusMinutes(1).toString(), "endsAt", clock.plusHours(1).toString(),
                "allowedMetrics", java.util.List.of("YFSI_MEAN"), "reason", "단독 운영 승인 테스트 사유");
        java.util.function.Supplier<MockHttpServletRequestBuilder> mutation = () ->
                post(transition).secure(true).header("Origin", ORIGIN).cookie(csrfCookie, session)
                        .header(header, token).contentType("application/json");
        postJson(mutation.get(), approval).andExpect(status().isUnprocessableEntity());
        postJson(mutation.get().header("Idempotency-Key", "approval-1"), approval)
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        postJson(mutation.get().header("Idempotency-Key", "approval-1"), approval)
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        postJson(mutation.get().header("Idempotency-Key", "approval-1"),
                Map.of("action", "REJECT", "expectedVersion", 0, "reason", "다른 요청 본문 테스트"))
                .andExpect(status().isConflict());
        http.perform(get("/api/v1/admin/audit-logs").cookie(session).param("targetId", snapshot.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].action").value("COMPARISON_APPROVE"))
                .andExpect(jsonPath("$.items[0].changes.reason").doesNotExist());
        var replacement = read(postJson(write.apply("/login"), credentials).andExpect(status().isOk()), "challengeId");
        postJson(write.apply("/mfa"), Map.of("challengeId", replacement, "code", code))
                .andExpect(status().isUnauthorized());
        db.sql("UPDATE admin_session SET last_interaction_at = now() - interval '10 minutes'").update();
        http.perform(get(PATH + "/me").cookie(session)).andExpect(status().isOk());
        assertTrue(db.sql("SELECT last_interaction_at < now() - interval '9 minutes' FROM admin_session")
                .query(Boolean.class).single());
        http.perform(write.apply("/activity").cookie(session)).andExpect(status().isNoContent());
        assertTrue(db.sql("SELECT last_interaction_at > now() - interval '1 minute' FROM admin_session")
                .query(Boolean.class).single());
        db.sql("UPDATE admin_account SET session_version = session_version + 1").update();
        http.perform(get(PATH + "/me").cookie(session)).andExpect(status().isUnauthorized());
        db.sql("UPDATE admin_session SET session_version = session_version + 1").update();
        db.sql("UPDATE admin_session SET last_interaction_at = now() - interval '31 minutes'").update();
        http.perform(get(PATH + "/me").cookie(session)).andExpect(status().isUnauthorized());
        http.perform(write.apply("/logout").cookie(session)).andExpect(status().isNoContent());
        assertEquals(0, db.sql("SELECT count(*) FROM admin_session").query(Integer.class).single());
    }

    private ResultActions postJson(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return http.perform(request.content(json.writeValueAsString(body)));
    }

    private String read(ResultActions result, String field) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get(field).asString();
    }
}
