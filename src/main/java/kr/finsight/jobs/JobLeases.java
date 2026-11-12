package kr.finsight.jobs;

import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("local")
@Component
public class JobLeases {
    public record Claim(String queue, UUID jobId, UUID token, OffsetDateTime claimedAt) {}

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(JobLeases.class);
    private final JdbcClient db;
    private final ScheduledThreadPoolExecutor heartbeat = new ScheduledThreadPoolExecutor(1, task -> {
        var thread = new Thread(task, "finsight-job-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    JobLeases(JdbcClient db) {
        this.db = db;
        heartbeat.setRemoveOnCancelPolicy(true);
    }

    public Optional<Claim> acquire(String queue, UUID id) {
        var token = UUID.randomUUID();
        return db.sql("""
                INSERT INTO job_execution_lease(queue,job_id,owner_token,claimed_at,expires_at)
                VALUES (:queue,:id,:token,statement_timestamp(),statement_timestamp()+interval '60 seconds')
                ON CONFLICT (queue,job_id) DO UPDATE SET owner_token=excluded.owner_token,
                    claimed_at=clock_timestamp(),expires_at=clock_timestamp()+interval '60 seconds'
                WHERE job_execution_lease.expires_at<=clock_timestamp()
                RETURNING claimed_at
                """).param("queue", queue).param("id", id).param("token", token)
                .query(OffsetDateTime.class).optional().map(start -> new Claim(queue, id, token, start));
    }

    public ScheduledFuture<?> keepAlive(Claim claim) {
        return heartbeat.scheduleWithFixedDelay(() -> {
            try {
                renew(claim);
            } catch (RuntimeException failure) {
                LOG.warn("작업 lease 갱신 실패 {} {} ({})", claim.queue(), claim.jobId(), failure.getClass().getSimpleName());
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    boolean renew(Claim claim) {
        return bind("""
                UPDATE job_execution_lease SET expires_at=clock_timestamp()+interval '60 seconds'
                WHERE queue=:queue AND job_id=:id AND owner_token=:token AND expires_at>clock_timestamp()
                """, claim).update() == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockOwned(Claim claim) {
        return bind("""
                SELECT owner_token FROM job_execution_lease
                WHERE queue=:queue AND job_id=:id AND owner_token=:token AND expires_at>clock_timestamp()
                FOR UPDATE
                """, claim).query(UUID.class).optional().isPresent();
    }

    public void release(Claim claim) {
        bind("DELETE FROM job_execution_lease WHERE queue=:queue AND job_id=:id AND owner_token=:token", claim).update();
    }

    private JdbcClient.StatementSpec bind(String sql, Claim claim) {
        return db.sql(sql).param("queue", claim.queue()).param("id", claim.jobId()).param("token", claim.token());
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    void expireAbandonedRows() {
        db.sql("DELETE FROM job_execution_lease WHERE expires_at<=clock_timestamp()-interval '1 hour'").update();
    }

    @PreDestroy
    void stop() { heartbeat.shutdownNow(); }
}
