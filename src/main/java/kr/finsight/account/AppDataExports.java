package kr.finsight.account;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import kr.finsight.jobs.JobLeases;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AppDataExports {
    record Job(UUID id, String status, OffsetDateTime requestedAt, OffsetDateTime expiresAt,
               int retryCount, OffsetDateTime nextRunAt) {}
    record Link(String path, Instant expiresAt) {}
    private record Pending(UUID id, UUID userId, long sourceEpoch, int retryCount) {}
    static final String QUEUE = "APP_DATA_EXPORT";
    private static final String JOB_FIELDS = "id,status,requested_at,expires_at,retry_count,next_run_at";
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AppDataExports.class);

    private final JdbcClient db;
    private final ExportArchive archives;
    private final SecretKey appJwtKey;
    private final JobLeases leases;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate commit;

    AppDataExports(JdbcClient db, ExportArchive archives, SecretKey appJwtKey, JobLeases leases,
                   PlatformTransactionManager transactions) {
        this.db = db;
        this.archives = archives;
        this.appJwtKey = appJwtKey;
        this.leases = leases;
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.commit = new TransactionTemplate(transactions);
    }

    @Transactional
    Job request(UUID user, UUID session) {
        var sourceEpoch = db.sql("SELECT content_event_epoch FROM app_user WHERE id=:user AND status='ACTIVE' FOR UPDATE")
                .param("user", user).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        var recent = db.sql("""
                SELECT coalesce(reauthenticated_at > clock_timestamp() - interval '5 minutes', false)
                FROM app_session WHERE id = :session AND user_id = :user AND expires_at > clock_timestamp()
                FOR UPDATE
                """).param("session", session).param("user", user).query(Boolean.class).optional();
        if (!recent.orElse(false)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var pending = db.sql("SELECT " + JOB_FIELDS + " FROM app_data_export WHERE user_id = :user AND status = 'PENDING'")
                .param("user", user).query(Job.class).optional();
        return pending.orElseGet(() -> db.sql("""
                INSERT INTO app_data_export(id,user_id,status,source_epoch) VALUES (:id,:user,'PENDING',:epoch)
                RETURNING
                """ + JOB_FIELDS).param("id", UUID.randomUUID()).param("user", user).param("epoch", sourceEpoch).query(Job.class).single());
    }

    Job latest(UUID user) {
        return db.sql("""
                SELECT e.id,e.status,e.requested_at,e.expires_at,e.retry_count,e.next_run_at
                FROM app_data_export e JOIN app_user u ON u.id = e.user_id
                WHERE u.id=:user AND u.status='ACTIVE' AND u.content_event_epoch=e.source_epoch
                ORDER BY e.requested_at DESC,e.id DESC LIMIT 1
                """).param("user", user).query(Job.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    Job completed(UUID user, UUID id) {
        return db.sql("""
                SELECT e.id,e.status,e.requested_at,e.expires_at,e.retry_count,e.next_run_at
                FROM app_data_export e JOIN app_user u ON u.id=e.user_id
                WHERE e.id=:id AND u.id=:user AND u.status='ACTIVE' AND u.content_event_epoch=e.source_epoch
                AND e.status='COMPLETED' AND e.archive IS NOT NULL AND e.expires_at>now()
                """).param("id", id).param("user", user).query(Job.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    Link link(UUID user, UUID exportId) {
        var available = db.sql("""
                SELECT e.expires_at FROM app_data_export e JOIN app_user u ON u.id = e.user_id
                WHERE e.id = :id AND u.id = :user AND u.status = 'ACTIVE' AND e.status = 'COMPLETED'
                    AND u.content_event_epoch=e.source_epoch AND e.expires_at > now() AND e.archive IS NOT NULL
                """).param("id", exportId).param("user", user).query(OffsetDateTime.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).toInstant();
        var fiveMinutes = Instant.now().plusSeconds(300);
        var expires = Instant.ofEpochSecond(Math.min(fiveMinutes.getEpochSecond(), available.getEpochSecond()));
        var seconds = expires.getEpochSecond();
        return new Link("/api/v1/account/export/download/" + exportId + "?expires=" + seconds
                + "&signature=" + HexFormat.of().formatHex(sign(exportId, seconds)), expires);
    }

    byte[] download(UUID exportId, long expires, String signature) {
        if (expires <= Instant.now().getEpochSecond() || !signature.matches("[a-f0-9]{64}")
                || !MessageDigest.isEqual(sign(exportId, expires), HexFormat.of().parseHex(signature))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return db.sql("""
                SELECT e.archive FROM app_data_export e JOIN app_user u ON u.id = e.user_id
                WHERE e.id = :id AND u.status = 'ACTIVE' AND e.status = 'COMPLETED'
                    AND u.content_event_epoch=e.source_epoch AND e.expires_at > clock_timestamp() AND e.archive IS NOT NULL
                """).param("id", exportId).query((row, index) -> row.getBytes(1)).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    boolean processNext() {
        var next = db.sql("""
                SELECT e.id,e.user_id,e.source_epoch,e.retry_count FROM app_data_export e JOIN app_user u ON u.id=e.user_id
                WHERE e.status='PENDING' AND u.status='ACTIVE' AND u.content_event_epoch=e.source_epoch
                    AND (e.next_run_at IS NULL OR e.next_run_at<=statement_timestamp())
                    AND NOT EXISTS(SELECT 1 FROM job_execution_lease l WHERE l.queue=:queue AND l.job_id=e.id
                        AND l.expires_at>statement_timestamp())
                ORDER BY e.requested_at,e.id LIMIT 1
                """).param("queue", QUEUE).query(Pending.class).optional();
        if (next.isEmpty()) return false;
        var job = next.get();
        var acquired = leases.acquire(QUEUE, job.id());
        if (acquired.isEmpty()) return false;
        var claim = acquired.get();
        var heartbeat = leases.keepAlive(claim);
        try {
            var bytes = snapshot.execute(status -> generate(job));
            if (bytes != null) commit.executeWithoutResult(status -> {
                if (leases.lockOwned(claim) && lockSource(job)) complete(job, claim, bytes);
            });
        } catch (RuntimeException failure) {
            LOG.warn("개인 데이터 내보내기 실패 {} ({})", job.id(), failure.getClass().getSimpleName());
            boolean retryable = failure instanceof UncheckedIOException || failure instanceof TransientDataAccessException
                    || failure instanceof DataAccessResourceFailureException || failure instanceof CannotCreateTransactionException;
            commit.executeWithoutResult(status -> {
                if (leases.lockOwned(claim) && lockSource(job)) recordFailure(job, claim, retryable);
            });
        } finally {
            heartbeat.cancel(false);
            leases.release(claim);
        }
        return true;
    }

    private byte[] generate(Pending job) {
        boolean eligible = db.sql("""
                SELECT EXISTS(SELECT 1 FROM app_data_export e JOIN app_user u ON u.id=e.user_id
                WHERE e.id=:id AND e.status='PENDING' AND e.retry_count=:retry AND e.source_epoch=:epoch
                    AND (e.next_run_at IS NULL OR e.next_run_at<=statement_timestamp())
                    AND u.status='ACTIVE' AND u.content_event_epoch=e.source_epoch)
                """).param("id", job.id()).param("retry", job.retryCount()).param("epoch", job.sourceEpoch())
                .query(Boolean.class).single();
        return eligible ? archives.build(job.userId()) : null;
    }

    private boolean lockSource(Pending job) {
        return db.sql("SELECT id FROM app_user WHERE id=:user AND status='ACTIVE' AND content_event_epoch=:epoch FOR UPDATE")
                .param("user", job.userId()).param("epoch", job.sourceEpoch()).query(UUID.class).optional().isPresent();
    }

    private void complete(Pending job, JobLeases.Claim claim, byte[] bytes) {
        db.sql("""
                UPDATE app_data_export SET status='COMPLETED',started_at=coalesce(started_at,:start),
                    completed_at=clock_timestamp(),expires_at=clock_timestamp()+interval '24 hours',
                    archive=:archive,next_run_at=NULL,error_code=NULL
                WHERE id=:id AND status='PENDING' AND retry_count=:retry AND source_epoch=:epoch
                """).param("id", job.id()).param("archive", bytes).param("start", claim.claimedAt())
                .param("retry", job.retryCount()).param("epoch", job.sourceEpoch()).update();
    }

    private void recordFailure(Pending job, JobLeases.Claim claim, boolean retryable) {
        db.sql("""
                UPDATE app_data_export SET
                    status=CASE WHEN :retryable AND retry_count<3 THEN 'PENDING' ELSE 'FAILED' END,
                    next_run_at=CASE WHEN :retryable AND retry_count<3 THEN clock_timestamp()+CASE retry_count
                        WHEN 0 THEN interval '1 minute' WHEN 1 THEN interval '5 minutes' ELSE interval '15 minutes' END END,
                    retry_count=retry_count+CASE WHEN :retryable AND retry_count<3 THEN 1 ELSE 0 END,
                    started_at=coalesce(started_at,:start),
                    completed_at=CASE WHEN NOT :retryable OR retry_count>=3 THEN clock_timestamp() END,
                    error_code=CASE WHEN NOT :retryable OR retry_count>=3 THEN 'ARCHIVE_BUILD_FAILED' END
                WHERE id=:id AND status='PENDING' AND retry_count=:retry AND source_epoch=:epoch
                """).param("id", job.id()).param("start", claim.claimedAt()).param("retryable", retryable)
                .param("retry", job.retryCount()).param("epoch", job.sourceEpoch()).update();
    }

    void expire() {
        db.sql("DELETE FROM app_data_export WHERE expires_at <= now()").update();
        db.sql("""
                UPDATE app_data_export e SET status='FAILED',completed_at=clock_timestamp(),error_code='QUEUE_EXPIRED',next_run_at=NULL
                WHERE e.status='PENDING' AND e.requested_at<=now()-interval '1 hour'
                    AND NOT EXISTS(SELECT 1 FROM job_execution_lease l WHERE l.queue=:queue AND l.job_id=e.id
                        AND l.expires_at>statement_timestamp())
                """).param("queue", QUEUE).update();
    }

    private byte[] sign(UUID id, long expires) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(appJwtKey);
            return mac.doFinal(("data-export:" + id + ":" + expires).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
