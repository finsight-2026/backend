package kr.finsight.account;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@Profile("local")
@ConditionalOnProperty(name = "finsight.admin.recovery.command", havingValue = "false", matchIfMissing = true)
class WithdrawalJob {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(WithdrawalJob.class);
    private final WithdrawalService withdrawals;

    WithdrawalJob(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void finalizeDueAccounts() {
        for (var purge : withdrawals.duePurges()) {
            try {
                withdrawals.finalizePurge(purge);
            } catch (RuntimeException failure) {
                LOG.warn("탈퇴 정리 실패 {} ({})", purge.requestId(), failure.getClass().getSimpleName());
                try {
                    withdrawals.recordPurgeFailure(purge);
                } catch (RuntimeException recordingFailure) {
                    LOG.error("탈퇴 정리 상태 기록 실패 {} ({})", purge.requestId(), recordingFailure.getClass().getSimpleName());
                }
            }
        }
    }
}
