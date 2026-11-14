package kr.finsight.diagnosis;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;
import kr.finsight.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MonthlyReportJobTest {
    @Autowired JdbcClient db;
    @Autowired JsonMapper json;
    @Autowired TransactionTemplate transactions;

    @Test
    @Transactional
    void closesOnKoreanTimeAndRecomputesAfterDeletingAnIntermediateDiagnosis() {
        var month = YearMonth.of(2026, 2);
        var closing = OffsetDateTime.parse("2026-03-01T09:00:00+09:00");
        assertEquals(closing, MonthlyReportJob.closesAt(month));
        var user = UUID.randomUUID();
        db.sql("INSERT INTO app_user(id,email,status) VALUES (:id,:email,'ACTIVE')")
                .param("id", user).param("email", user + "@example.test").update();
        var first = diagnosis(user, OffsetDateTime.parse("2026-01-31T15:00:00Z"), 3);
        var intermediate = diagnosis(user, OffsetDateTime.parse("2026-02-15T10:00:00+09:00"), 4);
        var last = diagnosis(user, OffsetDateTime.parse("2026-02-28T14:59:59Z"), 5);
        diagnosis(user, OffsetDateTime.parse("2026-02-28T15:00:00Z"), 3);
        db.sql("""
                INSERT INTO guide_activity(user_id,content_id,month_start,content_version,representative_type,title)
                VALUES (:id,'DEBT-1',:month,1,'DEBT','검증')
                """).param("id", user).param("month", month.atDay(1)).update();
        var job = new MonthlyReportJob(db, transactions);
        var controller = new MonthlyReportController(db, json);
        var jwt = Jwt.withTokenValue("fixture").header("alg", "none").subject(user.toString()).build();
        job.close(month, closing.minusSeconds(1));
        assertEquals(0L, storedCount(user));
        job.close(month, closing);
        job.close(month, closing.plusMinutes(1));
        assertEquals(3L, storedCount(user));
        assertEquals(closing.toInstant(), db.sql("SELECT closed_at FROM monthly_report_summary WHERE user_id=:id")
                .param("id", user).query(OffsetDateTime.class).single().toInstant());
        var closed = controller.current(jwt, month.toString()).getBody();
        assertNotNull(closed);
        assertEquals(last, closed.attemptId());
        assertEquals(3, closed.diagnosisCount());
        assertEquals(1, closed.guideViews());
        assertEquals(0, closed.guideCompletions());
        assertNotNull(closed.scoreChange());

        db.sql("DELETE FROM survey_attempt WHERE id=:id").param("id", intermediate).update();
        assertEquals(0L, storedCount(user));
        assertEquals(2L, controller.current(jwt, month.toString()).getBody().diagnosisCount());
        job.close(month, closing.plusMinutes(2));
        assertEquals(2L, storedCount(user));
        assertEquals(first, db.sql("SELECT first_attempt_id FROM monthly_report_summary WHERE user_id=:id")
                .param("id", user).query(UUID.class).single());
        db.sql("UPDATE guide_activity SET completed_at=clock_timestamp() WHERE user_id=:id")
                .param("id", user).update();
        assertEquals(0L, storedCount(user));
        assertEquals(1L, controller.current(jwt, month.toString()).getBody().guideCompletions());
        job.close(month, closing.plusMinutes(3));
        db.sql("DELETE FROM survey_attempt WHERE user_id=:id").param("id", user).update();
        assertEquals(0L, storedCount(user));
        var deleted = controller.current(jwt, month.toString()).getBody();
        assertFalse(deleted.available());
        assertNull(deleted.result());
    }

    private long storedCount(UUID user) {
        return db.sql("SELECT diagnosis_count FROM monthly_report_summary WHERE user_id=:id")
                .param("id", user).query(Long.class).optional().orElse(0L);
    }

    private UUID diagnosis(UUID user, OffsetDateTime at, int answer) {
        var attempt = UUID.randomUUID();
        db.sql("""
                INSERT INTO survey_attempt(id,user_id,survey_version,formula_version,started_at)
                VALUES (:id,:user,'fixture','fixture',:at)
                """).param("id", attempt).param("user", user).param("at", at.minusMinutes(1)).update();
        var result = YfsiScoring.calculate(java.util.Collections.nCopies(30, answer));
        db.sql("""
                INSERT INTO diagnosis(id,attempt_id,completed_at,representative_type,score_snapshot)
                VALUES (gen_random_uuid(),:attempt,:at,:type,CAST(:score AS jsonb))
                """).param("attempt", attempt).param("at", at).param("type", result.representativeType().name())
                .param("score", json.writeValueAsString(result)).update();
        return attempt;
    }
}
