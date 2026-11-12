package kr.finsight.account;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Profile("local")
@Component
@ConditionalOnProperty(name = "finsight.deletion.worker-enabled", havingValue = "true", matchIfMissing = true)
class DataDeletionJob {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(DataDeletionJob.class);
    private final DataDeletionService deletion;

    DataDeletionJob(DataDeletionService deletion) { this.deletion = deletion; }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    void process() {
        for (var job : deletion.dueJobs()) {
            try {
                deletion.purge(job);
            } catch (RuntimeException failure) {
                LOG.warn("서비스 데이터 삭제 실패 {} ({})", job.operationId(), failure.getClass().getSimpleName());
                try {
                    deletion.recordFailure(job);
                } catch (RuntimeException recordingFailure) {
                    LOG.error("서비스 데이터 삭제 상태 기록 실패 {} ({})", job.operationId(),
                            recordingFailure.getClass().getSimpleName());
                }
            }
        }
    }
}
