package kr.finsight.diagnosis;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Profile("local")
@Component
class MonthlyReportJob {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int BATCH_SIZE = 100;
    private final JdbcClient db;
    private final TransactionTemplate transactions;

    MonthlyReportJob(JdbcClient db, TransactionTemplate transactions) {
        this.db = db;
        this.transactions = transactions;
    }

    static OffsetDateTime closesAt(YearMonth month) {
        return month.plusMonths(1).atDay(1).atTime(9, 0).atZone(KST).toOffsetDateTime();
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    void closePreviousMonth() {
        var now = OffsetDateTime.now(KST);
        close(YearMonth.from(now).minusMonths(1), now);
    }

    void close(YearMonth month, OffsetDateTime now) {
        if (now.isBefore(closesAt(month))) return;
        for (int i = 0; i < 10; i++) {
            var processed = transactions.execute(status -> {
                var start = month.atDay(1);
                var accounts = db.sql("""
                        SELECT u.id FROM app_user u WHERE u.status='ACTIVE'
                            AND NOT EXISTS(SELECT 1 FROM monthly_report_summary s WHERE s.user_id=u.id AND s.month_start=:month)
                            AND EXISTS(SELECT 1 FROM survey_attempt a JOIN diagnosis d ON d.attempt_id=a.id
                                WHERE a.user_id=u.id AND d.completed_at>=:start AND d.completed_at<:end)
                        ORDER BY u.id LIMIT :size FOR NO KEY UPDATE OF u SKIP LOCKED
                        """).param("month", start).param("start", start.atStartOfDay(KST).toOffsetDateTime())
                        .param("end", month.plusMonths(1).atDay(1).atStartOfDay(KST).toOffsetDateTime())
                        .param("size", BATCH_SIZE).query(UUID.class).list();
                if (accounts.isEmpty()) return 0;
                db.sql("""
                        INSERT INTO monthly_report_summary(user_id,month_start,diagnosis_count,first_attempt_id,last_attempt_id,
                            guide_views,guide_completions,closed_at)
                        SELECT account,:month,r.diagnosis_count,r.first_attempt_id,r.last_attempt_id,
                            r.guide_views,r.guide_completions,:closed
                        FROM unnest(CAST(:accounts AS uuid[])) AS selected(account)
                        CROSS JOIN LATERAL monthly_report_source(account,:month) r WHERE r.diagnosis_count>0
                        ON CONFLICT(user_id,month_start) DO NOTHING
                        """).param("accounts", accounts.toArray(UUID[]::new)).param("month", start)
                        .param("closed", closesAt(month)).update();
                return accounts.size();
            });
            if (processed == null || processed < BATCH_SIZE) return;
        }
    }
}
