package kr.finsight.diagnosis;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
class MonthlyReportController {
    record Report(String month, boolean available, boolean provisional, OffsetDateTime closesAt,
                  long diagnosisCount, long guideViews, long guideCompletions, UUID attemptId,
                  OffsetDateTime completedAt, Integer firstScore, Integer scoreChange, YfsiScoring.Result result) {}

    private final JdbcClient db;
    private final JsonMapper json;

    MonthlyReportController(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    @GetMapping("/api/v1/diagnosis/monthly-report")
    ResponseEntity<Report> current(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String month) {
        var now = OffsetDateTime.now(MonthlyReportJob.KST);
        var current = YearMonth.from(now);
        var selected = parseMonth(month, current);
        var start = selected.atDay(1);
        var closesAt = MonthlyReportJob.closesAt(selected);
        boolean provisional = now.isBefore(closesAt);
        var report = db.sql("""
                WITH stored AS (
                    SELECT s.diagnosis_count,s.first_attempt_id,s.last_attempt_id,s.guide_views,s.guide_completions
                    FROM monthly_report_summary s JOIN app_user u ON u.id=s.user_id AND u.status='ACTIVE'
                    WHERE s.user_id=:user AND s.month_start=:monthStart AND NOT :provisional
                ), report AS (
                    SELECT * FROM stored
                    UNION ALL
                    SELECT * FROM monthly_report_source(:user,:monthStart) WHERE NOT EXISTS(SELECT 1 FROM stored)
                )
                SELECT r.diagnosis_count,r.guide_views,r.guide_completions,
                    latest.attempt_id,latest.completed_at,latest.score_snapshot,earliest.score_snapshot -> 'total' AS first_score
                FROM report r LEFT JOIN diagnosis latest ON latest.attempt_id=r.last_attempt_id
                    LEFT JOIN diagnosis earliest ON earliest.attempt_id=r.first_attempt_id
                """).param("user", UUID.fromString(jwt.getSubject())).param("monthStart", start)
                .param("provisional", provisional).query((row, index) -> {
            long count = row.getLong("diagnosis_count");
            if (count == 0) {
                return new Report(selected.toString(), false, provisional, closesAt, 0,
                        row.getLong("guide_views"), row.getLong("guide_completions"), null, null, null, null, null);
            }
            var result = json.readValue(row.getString("score_snapshot"), YfsiScoring.Result.class);
            Integer first = count > 1
                    ? json.readValue(row.getString("first_score"), YfsiScoring.Score.class).display() : null;
            return new Report(selected.toString(), true, provisional, closesAt, count, row.getLong("guide_views"),
                    row.getLong("guide_completions"), row.getObject("attempt_id", UUID.class),
                    row.getObject("completed_at", OffsetDateTime.class), first,
                    first == null ? null : result.total().display() - first, result);
        }).single();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report);
    }

    private static YearMonth parseMonth(String value, YearMonth current) {
        if (value == null) return current;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            var month = YearMonth.parse(value);
            if (month.isAfter(current) || month.getYear() < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            return month;
        } catch (DateTimeParseException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}
