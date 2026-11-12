package kr.finsight.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminDashboard {
    record Filter(LocalDate from, LocalDate to, String age, YfsiScoring.Pressure type) {
        private static final Set<String> AGES = Set.of("19_22", "23_25", "26_29", "30_PLUS");

        static Filter resolve(Filter input) {
            var today = LocalDate.now(AdminUsers.KST);
            var end = input == null || input.to() == null ? today : input.to();
            var start = input == null || input.from() == null ? end.minusDays(29) : input.from();
            var age = input == null ? null : input.age();
            if (start.isAfter(end) || end.isAfter(today) || end.minusDays(365).isAfter(start)
                    || (age != null && !AGES.contains(age))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return new Filter(start, end, age, input == null ? null : input.type());
        }
    }

    private static final String SCOPE = """
            WITH accounts AS (
                SELECT u.id,u.created_at
                FROM app_user u LEFT JOIN app_user_profile p ON p.user_id=u.id
                WHERE (u.status='ACTIVE' OR (u.status='WITHDRAWAL_PENDING'
                        AND u.withdrawal_due_at-interval '14 days'>=:end))
                    AND NOT u.comparison_test_account AND u.created_at<:end
                    AND (:age IS NULL OR p.attributes->>'age'=:age)
            ), latest AS (
                SELECT DISTINCT ON (a.user_id) a.user_id,d.representative_type,d.score_snapshot
                FROM accounts u JOIN survey_attempt a ON a.user_id=u.id JOIN diagnosis d ON d.attempt_id=a.id
                WHERE d.completed_at>=:start AND d.completed_at<:end
                ORDER BY a.user_id,d.completed_at DESC,d.id DESC
            ), population AS (
                SELECT u.* FROM accounts u LEFT JOIN latest d ON d.user_id=u.id
                WHERE :type IS NULL OR d.representative_type=:type
            ), attempts AS (
                SELECT a.id,a.started_at,a.aborted_at,d.completed_at
                FROM population p JOIN survey_attempt a ON a.user_id=p.id
                LEFT JOIN diagnosis d ON d.attempt_id=a.id
                WHERE a.started_at>=:start AND a.started_at<:end
            ), scores AS (
                SELECT d.representative_type,
                    (d.score_snapshot#>>'{total,numerator}')::numeric /
                    nullif((d.score_snapshot#>>'{total,denominator}')::numeric,0) AS yfsi,
                    (d.score_snapshot#>>'{perceivedStress,numerator}')::numeric /
                    nullif((d.score_snapshot#>>'{perceivedStress,denominator}')::numeric,0) AS perceived,
                    (SELECT sum((area#>>'{score,numerator}')::numeric)/17
                     FROM jsonb_array_elements(d.score_snapshot->'rankedPressures') area
                     HAVING sum((area#>>'{score,denominator}')::integer)=17) AS pressure,
                    (SELECT sum((area#>>'{score,numerator}')::numeric)/9
                     FROM jsonb_array_elements(d.score_snapshot->'resources') area
                     HAVING sum((area#>>'{score,denominator}')::integer)=9) AS resources
                FROM latest d JOIN population p ON p.id=d.user_id
            )
            """;

    record Totals(long registrations, long accounts, long attempts, long completedWithin24Hours, long provisionalAttempts,
                  long diagnosedUsers, BigDecimal meanYfsi, BigDecimal meanPerceivedStress,
                  BigDecimal meanPressure, BigDecimal meanResources, long yfsiCount, long perceivedStressCount,
                  long pressureCount, long resourcesCount) {}
    record Daily(LocalDate date, long registrations, long attempts, long completedWithin24Hours) {}
    record Bucket(int lower, long count) {}
    record TypeCount(String type, long count) {}
    record Activity(LocalDate observedFrom, Long periodActive, Long dau, Long mau,
                    long d7Eligible, long d7Returned) {}
    record CohortCell(LocalDate week, int offset, long registrations, Long active) {}
    record GuideRate(long resultUsers, long guideUsers) {}
    record SignupFunnel(long signups, long profiles, long started, long completed, long results, long guides,
                        long provisional) {}
    record ResponseTime(long measuredAttempts, Long averageActiveMs) {}
    record Dashboard(LocalDate from, LocalDate to, OffsetDateTime syncedAt, Totals totals,
                     List<Daily> daily, List<Bucket> scoreDistribution, List<TypeCount> typeDistribution,
                     Activity activity, List<CohortCell> cohorts, GuideRate guideRate, SignupFunnel signupFunnel,
                     ResponseTime responseTime) {}

    private final JdbcClient db;

    AdminDashboard(JdbcClient db) { this.db = db; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Dashboard load(LocalDate from, LocalDate to, String age, YfsiScoring.Pressure type) {
        var totals = query("""
                SELECT (SELECT count(*) FROM population WHERE created_at>=:start),
                       (SELECT count(*) FROM population),
                       (SELECT count(*) FROM attempts),
                       (SELECT count(*) FROM attempts WHERE completed_at<=started_at+interval '24 hours'),
                       (SELECT count(*) FROM attempts WHERE completed_at IS NULL AND aborted_at IS NULL AND started_at>now()-interval '24 hours'),
                       count(*),round(avg(yfsi),1),round(avg(perceived),1),round(avg(pressure),1),round(avg(resources),1),
                       count(yfsi),count(perceived),count(pressure),count(resources)
                FROM scores
                """, from, to, age, type).query((row, n) -> new Totals(row.getLong(1), row.getLong(2),
                row.getLong(3), row.getLong(4), row.getLong(5), row.getLong(6), row.getBigDecimal(7), row.getBigDecimal(8),
                row.getBigDecimal(9), row.getBigDecimal(10), row.getLong(11), row.getLong(12), row.getLong(13), row.getLong(14))).single();
        var daily = query("""
                , signup_days AS (
                    SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day,count(*) AS count
                    FROM population WHERE created_at>=:start GROUP BY 1
                ), attempt_days AS (
                    SELECT (started_at AT TIME ZONE 'Asia/Seoul')::date AS day,count(*) AS count,
                           count(*) FILTER(WHERE completed_at<=started_at+interval '24 hours') AS completed
                    FROM attempts GROUP BY 1
                )
                SELECT series.day::date,coalesce(s.count,0),coalesce(a.count,0),coalesce(a.completed,0)
                FROM generate_series(CAST(:from AS timestamp), CAST(:to AS timestamp), interval '1 day') series(day)
                LEFT JOIN signup_days s ON s.day=series.day::date LEFT JOIN attempt_days a ON a.day=series.day::date
                ORDER BY series.day
                """, from, to, age, type).param("from", from).param("to", to)
                .query((row, n) -> new Daily(row.getObject(1, LocalDate.class), row.getLong(2), row.getLong(3), row.getLong(4))).list();
        var distribution = query("""
                SELECT b.lower,count(s.yfsi) FROM generate_series(0,90,10) b(lower)
                LEFT JOIN scores s ON s.yfsi>=b.lower AND (s.yfsi<b.lower+10 OR (b.lower=90 AND s.yfsi=100))
                GROUP BY b.lower ORDER BY b.lower
                """, from, to, age, type).query((row, n) -> new Bucket(row.getInt(1), row.getLong(2))).list();
        var types = query("""
                SELECT types.kind,count(s.representative_type)
                FROM unnest(ARRAY['DEBT','INCOME','EMERGENCY_FUND','FIXED_COST','FUTURE','FAMILY'])
                    WITH ORDINALITY types(kind,position)
                LEFT JOIN scores s ON s.representative_type=types.kind
                GROUP BY types.kind,types.position ORDER BY types.position
                """, from, to, age, type).query((row, n) -> new TypeCount(row.getString(1), row.getLong(2))).list();
        var activity = activity(from, to, age, type);
        var cohorts = cohorts(from, to, age, type, activity.observedFrom());
        var guideRate = query("""
                , result_views AS (
                    SELECT p.id AS user_id,d.attempt_id,min(v.recorded_at) AS viewed_at
                    FROM population p JOIN survey_attempt a ON a.user_id=p.id JOIN diagnosis d ON d.attempt_id=a.id
                    JOIN diagnosis_view_event v ON v.attempt_id=d.attempt_id
                    WHERE d.completed_at>=:start AND d.completed_at<:end
                    AND v.content_id IS NULL AND v.recorded_at>=d.completed_at AND v.recorded_at<:end
                    GROUP BY p.id,d.attempt_id
                )
                SELECT count(DISTINCT user_id),count(DISTINCT user_id) FILTER (WHERE EXISTS (
                    SELECT 1 FROM diagnosis_view_event g WHERE g.attempt_id=r.attempt_id
                    AND g.content_id IS NOT NULL AND g.recorded_at>=r.viewed_at AND g.recorded_at<:end
                )) FROM result_views r
                """, from, to, age, type).query((row, n) -> new GuideRate(row.getLong(1), row.getLong(2))).single();
        return new Dashboard(from, to, db.sql("SELECT now()").query(OffsetDateTime.class).single(),
                totals, daily, distribution, types, activity, cohorts, guideRate,
                signupFunnel(from, to, age, type), responseTime(from, to, age, type));
    }

    private SignupFunnel signupFunnel(LocalDate from, LocalDate to, String age, YfsiScoring.Pressure type) {
        return query("""
                , signups AS (
                    SELECT p.id,p.created_at,p.created_at+interval '168 hours' AS cutoff
                    FROM population p WHERE p.created_at>=:start
                ), profile_ready AS (
                    SELECT s.id,c.recorded_at AS profile_at
                    FROM signups s JOIN app_user_profile p ON p.user_id=s.id
                    JOIN LATERAL (
                        SELECT max(recorded_at) AS recorded_at FROM (
                            SELECT document_kind,min(recorded_at) AS recorded_at
                            FROM app_user_consent WHERE user_id=s.id AND granted
                            AND document_kind IN ('TERMS','PRIVACY')
                            AND recorded_at BETWEEN s.created_at AND s.cutoff
                            GROUP BY document_kind
                        ) consent HAVING count(*)=2
                    ) c ON true
                ), started AS (
                    SELECT s.id,a.id AS attempt_id,a.started_at,s.cutoff
                    FROM signups s JOIN profile_ready p ON p.id=s.id
                    JOIN survey_attempt a ON a.user_id=s.id AND a.started_at BETWEEN p.profile_at AND s.cutoff
                ), completed AS (
                    SELECT a.*,d.completed_at FROM started a JOIN diagnosis d ON d.attempt_id=a.attempt_id
                    WHERE d.completed_at BETWEEN a.started_at AND a.cutoff
                ), results AS (
                    SELECT d.id,d.attempt_id,d.cutoff,r.viewed_at
                    FROM completed d JOIN LATERAL (
                        SELECT min(v.recorded_at) AS viewed_at
                        FROM diagnosis_view_event v WHERE v.attempt_id=d.attempt_id AND v.content_id IS NULL
                        AND v.recorded_at BETWEEN d.completed_at AND d.cutoff
                    ) r ON r.viewed_at IS NOT NULL
                )
                SELECT (SELECT count(*) FROM signups),(SELECT count(*) FROM profile_ready),
                       (SELECT count(DISTINCT id) FROM started),(SELECT count(DISTINCT id) FROM completed),
                       count(DISTINCT r.id),count(DISTINCT r.id) FILTER(WHERE EXISTS (
                           SELECT 1 FROM diagnosis_view_event v WHERE v.attempt_id=r.attempt_id
                           AND v.content_id IS NOT NULL AND v.recorded_at BETWEEN r.viewed_at AND r.cutoff)),
                       (SELECT count(*) FROM signups WHERE cutoff>now())
                FROM results r
                """, from, to, age, type).query((row, n) -> new SignupFunnel(row.getLong(1), row.getLong(2),
                row.getLong(3), row.getLong(4), row.getLong(5), row.getLong(6), row.getLong(7))).single();
    }

    private ResponseTime responseTime(LocalDate from, LocalDate to, String age, YfsiScoring.Pressure type) {
        return query("""
                , measured AS (
                    SELECT a.id,sum(e.active_ms) AS active_ms
                    FROM attempts a JOIN diagnosis_question_event e ON e.attempt_id=a.id AND e.kind='COMPLETED'
                    WHERE a.completed_at BETWEEN a.started_at AND a.started_at+interval '24 hours'
                    AND e.recorded_at BETWEEN a.started_at AND a.started_at+interval '24 hours'
                    GROUP BY a.id
                    HAVING count(DISTINCT e.question_no)=30 AND sum(e.active_ms)<=86400000
                )
                SELECT count(*),round(avg(active_ms))::bigint FROM measured
                """, from, to, age, type).query((row, n) -> new ResponseTime(row.getLong(1), row.getObject(2, Long.class))).single();
    }

    private Activity activity(LocalDate from, LocalDate to, String age, YfsiScoring.Pressure type) {
        return query("""
                , coverage AS (
                    SELECT greatest(first_full_date,(now() AT TIME ZONE 'Asia/Seoul')::date-399) AS first
                    FROM app_foreground_observation
                ), visits AS (
                    SELECT a.user_id,a.activity_date FROM app_foreground_day a JOIN population p ON p.id=a.user_id
                    WHERE a.activity_date BETWEEN least(:from,:to-29) AND :to
                ), d7 AS (
                    SELECT p.id,(p.created_at AT TIME ZONE 'Asia/Seoul')::date+7 AS day
                    FROM population p WHERE p.created_at>=:start
                ), retention AS (
                    SELECT count(*) AS eligible,count(a.user_id) AS returned
                    FROM d7 JOIN coverage c ON d7.day>=c.first
                    LEFT JOIN app_foreground_day a ON a.user_id=d7.id AND a.activity_date=d7.day
                    WHERE d7.day<=:to AND d7.day<(now() AT TIME ZONE 'Asia/Seoul')::date
                )
                SELECT c.first,
                    CASE WHEN :from>=c.first THEN count(DISTINCT user_id) FILTER (WHERE activity_date>=:from) END,
                    CASE WHEN :to>=c.first THEN count(DISTINCT user_id) FILTER (WHERE activity_date=:to) END,
                    CASE WHEN :to-29>=c.first THEN count(DISTINCT user_id) FILTER (WHERE activity_date>=:to-29) END,
                    r.eligible,r.returned
                FROM retention r LEFT JOIN coverage c ON true LEFT JOIN visits v ON true
                GROUP BY c.first,r.eligible,r.returned
                """, from, to, age, type).param("from", from).param("to", to)
                .query((row, n) -> new Activity(row.getObject(1, LocalDate.class), row.getObject(2, Long.class),
                        row.getObject(3, Long.class), row.getObject(4, Long.class), row.getLong(5), row.getLong(6))).single();
    }

    private List<CohortCell> cohorts(LocalDate from, LocalDate to, String age, YfsiScoring.Pressure type,
                                    LocalDate observedFrom) {
        return query("""
                , signups AS (
                    SELECT id,date_trunc('week',created_at AT TIME ZONE 'Asia/Seoul')::date AS week
                    FROM population WHERE created_at>=:start
                ), cohort_sizes AS (
                    SELECT week,count(*) AS registrations FROM signups GROUP BY week
                ), windows AS (
                    SELECT c.week,c.registrations,n,c.week+7*n AS start_day,c.week+7*n+6 AS end_day
                    FROM cohort_sizes c CROSS JOIN generate_series(0,7) n
                )
                SELECT w.week,w.n,w.registrations,
                    CASE WHEN w.start_day>=:observedFrom AND w.end_day<=:to
                        AND w.end_day<(now() AT TIME ZONE 'Asia/Seoul')::date
                        THEN count(DISTINCT a.user_id) END
                FROM windows w JOIN signups s ON s.week=w.week
                LEFT JOIN app_foreground_day a ON a.user_id=s.id AND a.activity_date BETWEEN w.start_day AND w.end_day
                GROUP BY w.week,w.n,w.registrations,w.start_day,w.end_day ORDER BY w.week DESC,w.n
                """, from, to, age, type).param("to", to).param("observedFrom", observedFrom, java.sql.Types.DATE)
                .query((row, n) -> new CohortCell(row.getObject(1, LocalDate.class), row.getInt(2),
                        row.getLong(3), row.getObject(4, Long.class))).list();
    }

    private JdbcClient.StatementSpec query(String sql, LocalDate from, LocalDate to, String age, YfsiScoring.Pressure type) {
        return db.sql(SCOPE + sql)
                .param("start", from.atStartOfDay(AdminUsers.KST).toOffsetDateTime())
                .param("end", to.plusDays(1).atStartOfDay(AdminUsers.KST).toOffsetDateTime())
                .param("age", age, java.sql.Types.VARCHAR)
                .param("type", type == null ? null : type.name(), java.sql.Types.VARCHAR);
    }
}
