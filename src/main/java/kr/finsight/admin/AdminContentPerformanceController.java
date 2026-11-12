package kr.finsight.admin;

import java.time.LocalDate;
import kr.finsight.content.ContentEvents;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/analytics/content-performance")
class AdminContentPerformanceController {
    private final AdminAuth auth;
    private final AdminContentPerformance performance;

    AdminContentPerformanceController(AdminAuth auth, AdminContentPerformance performance) {
        this.auth = auth;
        this.performance = performance;
    }

    @GetMapping
    ResponseEntity<AdminContentPerformance.Report> list(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) ContentEvents.Kind kind, @RequestParam(required = false) YfsiScoring.Pressure type,
            @RequestParam(required = false) String contentId, @RequestParam(required = false) Integer revision,
            @RequestParam(defaultValue = "false") boolean combineVersions) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(performance.list(auth.identity(session),
                new AdminContentPerformance.Filter(from, to, kind, type, contentId, revision, combineVersions)));
    }
}
