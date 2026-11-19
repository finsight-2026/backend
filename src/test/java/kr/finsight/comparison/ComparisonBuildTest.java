package kr.finsight.comparison;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.finsight.TestcontainersConfiguration;
import kr.finsight.diagnosis.YfsiScoring;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "finsight.auth.jwt-key=cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd",
        "finsight.signup.code-key=abababababababababababababababababababababababababababababababab"
})
class ComparisonBuildTest {
    @Autowired JdbcClient db;
    @Autowired ComparisonBuild build;
    @Autowired ComparisonPublishing publishing;
    @Autowired ComparisonRead read;
    @Autowired kr.finsight.jobs.JobLeases leases;

    @Test
    void cohortUsesLatestCompletedDiagnosisAndExcludesTestAndPendingAccounts() {
        var admin = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,'build@example.test','fixture','test',decode('00','hex'),'ACTIVE')
                """).param("id", admin).update();
        var moved = account(false, "ACTIVE");
        diagnosis(moved, "DEBT", 1, "yfsi-v1");
        diagnosis(moved, "INCOME", 0, "yfsi-v1");
        var stayed = account(false, "ACTIVE");
        diagnosis(stayed, "DEBT", 1, "yfsi-v1");
        diagnosis(stayed, "DEBT", 0, "yfsi-v1");
        diagnosis(account(true, "ACTIVE"), "DEBT", 0, "yfsi-v1");
        diagnosis(account(false, "WITHDRAWAL_PENDING"), "DEBT", 0, "yfsi-v1");
        diagnosis(account(false, "ACTIVE"), "DEBT", 0, "different");
        var end = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var pending = build.request(admin, YfsiScoring.Pressure.DEBT, end.minusDays(29), end, "yfsi-v1");
        assertEquals(ComparisonBuild.Status.BUILDING, pending.status());
        assertEquals(pending.id(), build.request(admin, YfsiScoring.Pressure.DEBT, end.minusDays(29), end, "yfsi-v1").id());
        leases.acquire(ComparisonBuild.QUEUE, pending.id()).orElseThrow();
        assertFalse(build.processNext());
        db.sql("UPDATE job_execution_lease SET expires_at=clock_timestamp()-interval '1 second' WHERE job_id=:id")
                .param("id", pending.id()).update();
        assertTrue(build.processNext());
        var ready = build.get(pending.id());
        assertEquals(ComparisonBuild.Status.READY, ready.status());
        assertEquals(1, ready.sampleSize());
        assertEquals(3, ready.excludedSize());
        assertEquals(50, ready.metrics().path("YFSI_MEAN").path("mean").asInt());
        assertEquals(ComparisonBuild.Status.IN_REVIEW, build.submit(ready.id(), ready.version()).status());
    }

    @Test
    void dataVersionChangedDuringBuildDiscardsMembersAndRejectsReview() {
        var pending = buildWithDiagnosis(YfsiScoring.Pressure.FUTURE);
        db.sql("""
                CREATE FUNCTION test_comparison_source_change() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN UPDATE comparison_dataset SET version=version+1 WHERE type='FUTURE'; RETURN NEW; END;
                $$
                """).update();
        db.sql("""
                CREATE TRIGGER test_comparison_source_change AFTER INSERT ON comparison_snapshot_member
                FOR EACH ROW WHEN (NEW.snapshot_id='%s'::uuid) EXECUTE FUNCTION test_comparison_source_change()
                """.formatted(pending.id())).update();
        try {
            assertTrue(build.processNext());
            var failed = build.get(pending.id());
            assertEquals(ComparisonBuild.Status.FAILED, failed.status());
            assertEquals("SOURCE_CHANGED", failed.failureReason());
            assertEquals(0, db.sql("SELECT count(*) FROM comparison_snapshot_member WHERE snapshot_id=:id")
                    .param("id", pending.id()).query(Integer.class).single());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> build.submit(pending.id(), failed.version()));
        } finally {
            db.sql("DROP TRIGGER test_comparison_source_change ON comparison_snapshot_member").update();
            db.sql("DROP FUNCTION test_comparison_source_change()").update();
        }
        var fresh = buildWithDiagnosis(YfsiScoring.Pressure.FUTURE);
        assertTrue(build.processNext());
        var ready = build.get(fresh.id());
        assertEquals(ComparisonBuild.Status.READY, ready.status());
        db.sql("UPDATE comparison_dataset SET version=version+1 WHERE type='FUTURE'").update();
        assertEquals(409, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> build.submit(ready.id(), ready.version())).getStatusCode().value());
    }

    @Test
    void transientDatabaseFailureUsesThreeBackoffsAndLeavesNoPartialCohort() {
        var pending = buildWithDiagnosis(YfsiScoring.Pressure.FIXED_COST);
        db.sql("""
                CREATE FUNCTION test_comparison_transient_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'fixture serialization failure' USING ERRCODE='40001'; END;
                $$
                """).update();
        db.sql("""
                CREATE TRIGGER test_comparison_transient_failure BEFORE INSERT ON comparison_snapshot_member
                FOR EACH ROW WHEN (NEW.snapshot_id='%s'::uuid) EXECUTE FUNCTION test_comparison_transient_failure()
                """.formatted(pending.id())).update();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                assertTrue(build.processNext());
                var delayed = build.get(pending.id());
                assertEquals(attempt + 1, delayed.retryCount());
                assertEquals(ComparisonBuild.Status.BUILDING, delayed.status());
                var seconds = db.sql("SELECT extract(epoch FROM next_run_at-clock_timestamp()) FROM comparison_snapshot WHERE id=:id")
                        .param("id", pending.id()).query(Double.class).single();
                var expected = java.util.List.of(60, 300, 900).get(attempt);
                assertTrue(seconds > expected - 5 && seconds <= expected);
                assertFalse(build.processNext());
                db.sql("UPDATE comparison_snapshot SET next_run_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                        .param("id", pending.id()).update();
            }
            assertTrue(build.processNext());
            var failed = build.get(pending.id());
            assertEquals(ComparisonBuild.Status.FAILED, failed.status());
            assertEquals("BUILD_FAILED", failed.failureReason());
            assertNull(failed.nextRunAt());
            assertEquals(0, db.sql("SELECT count(*) FROM comparison_snapshot_member WHERE snapshot_id=:id")
                    .param("id", pending.id()).query(Integer.class).single());
        } finally {
            db.sql("DROP TRIGGER test_comparison_transient_failure ON comparison_snapshot_member").update();
            db.sql("DROP FUNCTION test_comparison_transient_failure()").update();
        }
    }

    private ComparisonBuild.Snapshot buildWithDiagnosis(YfsiScoring.Pressure pressure) {
        var admin = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'fixture','test',decode('00','hex'),'ACTIVE')
                """).param("id", admin).param("email", admin + "@example.test").update();
        diagnosis(account(false, "ACTIVE"), pressure.name(), 0, "yfsi-v1");
        return build.request(admin, pressure, null, null, "yfsi-v1");
    }

    @Test
    void approvalRequiresThresholdAndReasonAndUnpublishRemovesTheActiveVersion() {
        var actor = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,'publish@example.test','fixture','test',decode('00','hex'),'ACTIVE')
                """).param("id", actor).update();
        var id = UUID.randomUUID();
        db.sql("""
                INSERT INTO comparison_snapshot
                    (id,type,period_start,period_end,formula_version,dataset_version,sample_n,metrics,status,created_by)
                SELECT :id,type,current_date-29,current_date,'yfsi-v1',version,30,
                    '{"YFSI_MEAN":{"n":30,"mean":50},"EXTERNAL_AREA_MEAN":{
                        "DEBT":{"n":30,"mean":45},"INCOME":{"n":29,"mean":60},"FAMILY":{"n":31,"mean":80}
                    }}','IN_REVIEW',:actor
                FROM comparison_dataset WHERE type = 'FAMILY'
                """).param("id", id).param("actor", actor).update();
        var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        var approve = new ComparisonPublishing.Change(ComparisonPublishing.Action.APPROVE, 0L,
                java.util.List.of("YFSI_MEAN"), now.minusMinutes(1), now.plusHours(1),
                "단독 운영으로 집계 승인", null);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                assertThrows(ResponseStatusException.class, () -> publishing.transition(actor, id, approve)).getStatusCode());
        assertTrue(publishing.preview(id).metrics().isEmpty());
        publishing.updateMinimum(YfsiScoring.Pressure.FAMILY, 30, 0, "공개 표본 기준을 설정합니다");

        db.sql("UPDATE comparison_snapshot SET sample_n=29 WHERE id=:id").param("id", id).update();
        assertTrue(publishing.preview(id).metrics().isEmpty());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                assertThrows(ResponseStatusException.class, () -> publishing.transition(actor, id, approve)).getStatusCode());
        assertEquals(ComparisonBuild.Status.IN_REVIEW, build.get(id).status());
        assertFalse(publishing.publications().stream().anyMatch(row -> row.snapshotId().equals(id)));
        db.sql("UPDATE comparison_snapshot SET sample_n=30 WHERE id=:id").param("id", id).update();

        var preview = publishing.preview(id);
        assertEquals(45, preview.metrics().path("EXTERNAL_AREA_MEAN").path("DEBT").asInt());
        assertEquals(1, preview.metrics().path("EXTERNAL_AREA_MEAN").size());
        assertNull(preview.startsAt());
        var noReason = new ComparisonPublishing.Change(ComparisonPublishing.Action.APPROVE, 0L,
                approve.allowedMetrics(), approve.startsAt(), approve.endsAt(), null, null);
        var noMetrics = new ComparisonPublishing.Change(ComparisonPublishing.Action.APPROVE, 0L,
                List.of(), approve.startsAt(), approve.endsAt(), approve.reason(), null);
        var pastEnd = new ComparisonPublishing.Change(ComparisonPublishing.Action.APPROVE, 0L,
                approve.allowedMetrics(), now.minusHours(1), now.minusMinutes(1), approve.reason(), null);
        for (var invalid : List.of(noReason, noMetrics, pastEnd)) {
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                    assertThrows(ResponseStatusException.class, () -> publishing.transition(actor, id, invalid)).getStatusCode());
            assertEquals(0L, build.get(id).version());
        }
        var approved = publishing.transition(actor, id, approve);
        assertEquals(ComparisonBuild.Status.APPROVED, approved.status());
        var approvedPreview = publishing.preview(id);
        assertEquals(approved.version(), approvedPreview.version());
        assertEquals(1, approvedPreview.metrics().size());
        assertEquals(50, approvedPreview.metrics().path("YFSI_MEAN").asInt());
        assertEquals(approve.startsAt().toEpochSecond(), approvedPreview.startsAt().toEpochSecond());
        var published = publishing.transition(actor, id, new ComparisonPublishing.Change(
                ComparisonPublishing.Action.PUBLISH, approved.version(), null, null, null, null, null));
        assertTrue(publishing.publications().stream().anyMatch(row -> row.snapshotId().equals(id) && row.status().equals("ACTIVE")));
        publishing.transition(actor, id, new ComparisonPublishing.Change(
                ComparisonPublishing.Action.UNPUBLISH, published.version(), null, null, null, "운영 검토에 따라 공개 중지", null));
        assertFalse(publishing.publications().stream().anyMatch(row -> row.snapshotId().equals(id)));
    }

    @Test
    @Transactional
    void scheduledPublicationEnforcesWindowAndExplicitReplacement() {
        var actor = UUID.randomUUID();
        db.sql("""
                INSERT INTO admin_account(id,email,name,password_hash,totp_secret_encrypted,status)
                VALUES (:id,:email,'fixture','test',decode('00','hex'),'ACTIVE')
                """).param("id", actor).param("email", actor + "@example.test").update();
        var reader = account(true, "ACTIVE");
        diagnosis(reader, "FUTURE", 0, "yfsi-v1");
        publishing.updateMinimum(YfsiScoring.Pressure.FUTURE, 30, 0, "공개 표본 기준을 설정합니다");
        var now = db.sql("SELECT now()").query(OffsetDateTime.class).single();
        var scheduled = approveFutureComparison(actor, now.plusHours(1), now.plusHours(2), 50);
        var overlap = approveFutureComparison(actor, now.plusMinutes(90), now.plusHours(3), 60);

        publishing.transition(actor, scheduled.id(), publicationAction(ComparisonPublishing.Action.SCHEDULE, scheduled, null));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> publishing.transition(actor, overlap.id(),
                        publicationAction(ComparisonPublishing.Action.SCHEDULE, overlap, null))).getStatusCode());
        publishing.promote("FUTURE");
        assertEquals("SCHEDULED", publishing.publications().stream().filter(row -> row.snapshotId().equals(scheduled.id()))
                .findFirst().orElseThrow().status());
        var beforeStart = read.current(reader);
        assertEquals(false, beforeStart.get("available"));
        assertFalse(beforeStart.containsKey("metrics"));

        db.sql("UPDATE comparison_publication SET starts_at=now() WHERE snapshot_id=:id").param("id", scheduled.id()).update();
        assertEquals(false, read.current(reader).get("available"));
        publishing.promote("FUTURE");
        assertEquals(true, read.current(reader).get("available"));
        var publication = publishing.publications().stream().filter(row -> row.snapshotId().equals(scheduled.id()))
                .findFirst().orElseThrow();
        assertEquals("ACTIVE", publication.status());

        var replacement = approveFutureComparison(actor, now.minusMinutes(1), now.plusHours(1), 70);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> publishing.transition(actor, replacement.id(),
                        publicationAction(ComparisonPublishing.Action.PUBLISH, replacement, null))).getStatusCode());
        publishing.transition(actor, replacement.id(),
                publicationAction(ComparisonPublishing.Action.PUBLISH, replacement, publication.id()));
        var active = publishing.publications().stream().filter(row -> row.type().equals("FUTURE")).toList();
        assertEquals(1, active.size());
        assertEquals(replacement.id(), active.getFirst().snapshotId());
        assertEquals(70, ((JsonNode) read.current(reader).get("metrics")).path("YFSI_MEAN").asInt());

        db.sql("UPDATE comparison_publication SET ends_at=now() WHERE snapshot_id=:id").param("id", replacement.id()).update();
        var expired = read.current(reader);
        assertEquals(false, expired.get("available"));
        assertEquals("EXPIRED", expired.get("reason"));
        assertFalse(expired.containsKey("metrics"));
    }

    private ComparisonBuild.Snapshot approveFutureComparison(UUID actor, OffsetDateTime start, OffsetDateTime end, int mean) {
        var id = UUID.randomUUID();
        db.sql("""
                INSERT INTO comparison_snapshot
                    (id,type,period_start,period_end,formula_version,dataset_version,sample_n,metrics,status,created_by)
                SELECT :id,type,current_date-29,current_date,'yfsi-v1',version,30,
                    jsonb_build_object('YFSI_MEAN',jsonb_build_object('n',30,'mean',:mean)),'IN_REVIEW',:actor
                FROM comparison_dataset WHERE type='FUTURE'
                """).param("id", id).param("mean", mean).param("actor", actor).update();
        return publishing.transition(actor, id, new ComparisonPublishing.Change(ComparisonPublishing.Action.APPROVE,
                0L, List.of("YFSI_MEAN"), start, end, "단독 운영으로 집계 승인", null));
    }

    private ComparisonPublishing.Change publicationAction(ComparisonPublishing.Action action,
            ComparisonBuild.Snapshot snapshot, UUID replacePublication) {
        return new ComparisonPublishing.Change(action, snapshot.version(), null, null, null, null, replacePublication);
    }

    private UUID account(boolean test, String status) {
        var id = UUID.randomUUID();
        db.sql("INSERT INTO app_user(id,email,status,comparison_test_account) VALUES (:id,:email,:status,:test)")
                .param("id", id).param("email", id + "@example.test").param("status", status).param("test", test).update();
        return id;
    }

    private void diagnosis(UUID user, String type, int daysAgo, String formula) {
        var attempt = UUID.randomUUID();
        db.sql("""
                INSERT INTO survey_attempt(id,user_id,survey_version,formula_version,started_at)
                VALUES (:id,:user,'survey',:formula,now())
                """).param("id", attempt).param("user", user).param("formula", formula).update();
        db.sql("""
                INSERT INTO diagnosis(attempt_id,id,completed_at,representative_type,score_snapshot)
                VALUES (:attempt,:id,now() - :days * interval '1 day',:type,
                  '{"total":{"numerator":1300,"denominator":26},"rankedPressures":[],"resources":[]}')
                """).param("attempt", attempt).param("id", UUID.randomUUID()).param("days", daysAgo)
                .param("type", type).update();
    }
}
