package kr.finsight.admin;

import java.time.LocalDate;
import java.util.Set;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
class AdminDashboardController {
    private static final Set<String> ROLES = Set.of("ANALYST", "OPERATOR", "PRIVACY", "SUPER_ADMIN");
    private final AdminAuth auth;
    private final AdminDashboard dashboard;

    AdminDashboardController(AdminAuth auth, AdminDashboard dashboard) {
        this.auth = auth;
        this.dashboard = dashboard;
    }

    @GetMapping
    ResponseEntity<AdminDashboard.Dashboard> summary(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String age, @RequestParam(required = false) YfsiScoring.Pressure type) {
        var identity = auth.identity(token);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (identity.permissions().stream().noneMatch(ROLES::contains)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var filter = AdminDashboard.Filter.resolve(new AdminDashboard.Filter(from, to, age, type));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(dashboard.load(filter.from(), filter.to(), filter.age(), filter.type()));
    }
}
