package kr.finsight.comparison;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
class ComparisonBuildJob {
    private final ComparisonBuild build;
    private final ComparisonPublishing publishing;

    ComparisonBuildJob(ComparisonBuild build, ComparisonPublishing publishing) {
        this.build = build;
        this.publishing = publishing;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 30000)
    void process() {
        for (int batch = 0; batch < 10 && build.processNext(); batch++) {
            // Each snapshot is committed independently.
        }
    }
    @Scheduled(fixedDelay = 10000, initialDelay = 30000)
    void activateScheduled() {
        publishing.dueTypes().forEach(publishing::promote);
    }

}
