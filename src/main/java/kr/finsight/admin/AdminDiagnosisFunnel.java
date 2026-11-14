package kr.finsight.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import kr.finsight.diagnosis.SurveyCatalog;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@RequestMapping("/api/v1/admin/diagnosis-funnel")
class AdminDiagnosisFunnel {
    private static final Set<String> ROLES = Set.of("ANALYST", "OPERATOR", "PRIVACY", "SUPER_ADMIN");
    private final AdminAuth auth;
    private final JdbcClient db;
    private final SurveyCatalog catalog;
    private final AdminOperatingSettings settings;

    AdminDiagnosisFunnel(AdminAuth auth, JdbcClient db, SurveyCatalog catalog, AdminOperatingSettings settings) {
        this.auth = auth;
        this.db = db;
        this.catalog = catalog;
        this.settings = settings;
    }

    record Funnel(LocalDate from, LocalDate to, long entries, long started, long q1, long q15, long submitted,
                  long results, long guides, long provisional) {}

    record Question(int number, String text, long reached, long answered, long dropped, Long averageActiveMs,
                    List<Long> distribution) {}
    record Report(Funnel funnel, List<Question> questions, AdminOperatingSettings.FunnelLimits limits) {}

    @GetMapping
    ResponseEntity<Report> read(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        var identity = auth.identity(token);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (identity.permissions().stream().noneMatch(ROLES::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var today = LocalDate.now(AdminUsers.KST);
        var end = to == null ? today : to;
        var start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || end.isAfter(today) || end.minusDays(365).isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Report(load(start, end), questions(start, end), settings.funnelLimits()));
    }

    List<Question> questions(LocalDate from, LocalDate to) {
        return db.sql("""
                WITH attempts AS (
                    SELECT a.id,a.started_at,a.aborted_at,a.last_question,d.completed_at,d.id AS diagnosis_id,
                           a.started_at+interval '24 hours' AS cutoff
                    FROM survey_attempt a JOIN app_user u ON u.id=a.user_id LEFT JOIN diagnosis d ON d.attempt_id=a.id
                    WHERE u.status='ACTIVE' AND NOT u.comparison_test_account
                    AND a.survey_version=:version AND a.started_at>=:from AND a.started_at<:to
                ), events AS (
                    SELECT e.* FROM diagnosis_question_event e JOIN attempts a ON a.id=e.attempt_id
                    WHERE e.recorded_at<=a.cutoff
                ), by_attempt AS (
                    SELECT attempt_id,question_no,bool_or(kind='VIEWED') AS reached,
                           bool_or(kind='COMPLETED') AS answered,sum(active_ms) AS active_ms
                    FROM events GROUP BY attempt_id,question_no
                ), latest AS (
                    SELECT DISTINCT ON (attempt_id) attempt_id,question_no
                    FROM events WHERE kind='VIEWED' ORDER BY attempt_id,recorded_at DESC,id DESC
                ), drops AS (
                    SELECT a.id,coalesce(a.last_question,l.question_no) AS question_no
                    FROM attempts a LEFT JOIN latest l ON l.attempt_id=a.id
                    WHERE a.aborted_at IS NOT NULL OR (a.cutoff<=now() AND (a.completed_at IS NULL OR a.completed_at>a.cutoff))
                ), activity AS (
                    SELECT b.question_no,count(*) FILTER(WHERE b.reached) AS reached,
                           count(*) FILTER(WHERE b.answered) AS answered,
                           count(d.id) FILTER(WHERE b.reached) AS dropped,
                           round(avg(b.active_ms) FILTER(WHERE b.active_ms<=86400000))::bigint AS average_ms
                    FROM by_attempt b LEFT JOIN drops d ON d.id=b.attempt_id AND d.question_no=b.question_no
                    GROUP BY b.question_no
                ), distributions AS (
                    SELECT da.question_no,count(*) FILTER(WHERE da.response=1) AS r1,
                           count(*) FILTER(WHERE da.response=2) AS r2,count(*) FILTER(WHERE da.response=3) AS r3,
                           count(*) FILTER(WHERE da.response=4) AS r4,count(*) FILTER(WHERE da.response=5) AS r5
                    FROM attempts a JOIN diagnosis_answer da ON da.diagnosis_id=a.diagnosis_id GROUP BY da.question_no
                )
                SELECT q.no,coalesce(a.reached,0),coalesce(a.answered,0),coalesce(a.dropped,0),a.average_ms,
                       coalesce(d.r1,0),coalesce(d.r2,0),coalesce(d.r3,0),coalesce(d.r4,0),coalesce(d.r5,0)
                FROM generate_series(1,30) q(no)
                LEFT JOIN activity a ON a.question_no=q.no
                LEFT JOIN distributions d ON d.question_no=q.no
                ORDER BY q.no
                """).param("from", from.atStartOfDay(AdminUsers.KST).toOffsetDateTime())
                .param("to", to.plusDays(1).atStartOfDay(AdminUsers.KST).toOffsetDateTime())
                .param("version", catalog.current().version())
                .query((row, n) -> new Question(row.getInt(1), catalog.current().questions().get(n).text(),
                        row.getLong(2), row.getLong(3), row.getLong(4), row.getObject(5, Long.class),
                        List.of(row.getLong(6), row.getLong(7), row.getLong(8), row.getLong(9), row.getLong(10)))).list();
    }

    Funnel load(LocalDate from, LocalDate to) {
        return db.sql("""
                WITH observed AS (
                    SELECT j.entered_at,a.id,a.started_at,
                        coalesce(a.started_at,j.entered_at)+interval '24 hours' AS cutoff,
                        q1.recorded_at AS first_at,q15.recorded_at AS middle_at,d.completed_at
                    FROM diagnosis_journey j JOIN app_user u ON u.id=j.user_id
                    LEFT JOIN survey_attempt a ON a.journey_id=j.id
                    LEFT JOIN diagnosis_milestone q1 ON q1.attempt_id=a.id AND q1.kind='Q1_VIEWED'
                    LEFT JOIN diagnosis_milestone q15 ON q15.attempt_id=a.id AND q15.kind='Q15_COMPLETED'
                    LEFT JOIN diagnosis d ON d.attempt_id=a.id
                    WHERE u.status='ACTIVE' AND NOT u.comparison_test_account AND j.entered_at>=:start AND j.entered_at<:end
                ), valid AS (
                    SELECT *,
                        first_at BETWEEN started_at AND cutoff AS has_q1,
                        middle_at BETWEEN first_at AND cutoff AS has_q15,
                        completed_at BETWEEN middle_at AND cutoff AS has_submission
                    FROM observed
                ), viewed AS (
                    SELECT v.*,r.result_at,g.guide_at
                    FROM valid v
                    LEFT JOIN LATERAL (
                        SELECT min(recorded_at) AS result_at FROM diagnosis_view_event
                        WHERE attempt_id=v.id AND content_id IS NULL AND recorded_at BETWEEN v.completed_at AND v.cutoff
                    ) r ON true
                    LEFT JOIN LATERAL (
                        SELECT min(recorded_at) AS guide_at FROM diagnosis_view_event
                        WHERE attempt_id=v.id AND content_id IS NOT NULL AND recorded_at BETWEEN r.result_at AND v.cutoff
                    ) g ON true
                )
                SELECT count(*),count(started_at),count(*) FILTER(WHERE has_q1),
                    count(*) FILTER(WHERE has_q1 AND has_q15),
                    count(*) FILTER(WHERE has_q1 AND has_q15 AND has_submission),
                    count(*) FILTER(WHERE has_q1 AND has_q15 AND has_submission AND result_at IS NOT NULL),
                    count(*) FILTER(WHERE has_q1 AND has_q15 AND has_submission AND guide_at IS NOT NULL),
                    count(*) FILTER(WHERE cutoff>now())
                FROM viewed
                """).param("start", from.atStartOfDay(AdminUsers.KST).toOffsetDateTime())
                .param("end", to.plusDays(1).atStartOfDay(AdminUsers.KST).toOffsetDateTime())
                .query((row, n) -> new Funnel(from, to, row.getLong(1), row.getLong(2), row.getLong(3),
                        row.getLong(4), row.getLong(5), row.getLong(6), row.getLong(7), row.getLong(8))).single();
    }
}
