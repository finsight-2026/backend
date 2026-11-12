package kr.finsight.account;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
class AppDataExportJob {
    private final AppDataExports exports;

    AppDataExportJob(AppDataExports exports) {
        this.exports = exports;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 30000)
    void processExports() {
        exports.expire();
        for (int batch = 0; batch < 20 && exports.processNext(); batch++) {
            // Each archive is committed separately.
        }
    }
}
