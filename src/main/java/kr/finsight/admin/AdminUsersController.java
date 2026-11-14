package kr.finsight.admin;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/users")
class AdminUsersController {
    private final AdminAuth auth;
    private final AdminUsers users;

    AdminUsersController(AdminAuth auth, AdminUsers users) {
        this.auth = auth;
        this.users = users;
    }

    @GetMapping
    AdminUsers.Page<AdminUsers.User> list(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @RequestParam(required = false) UUID userId, @RequestParam(required = false) UUID diagnosisId,
            @RequestParam(required = false) YfsiScoring.Pressure type, @RequestParam(required = false) String age,
            @RequestParam(required = false) AdminUsers.Status status, @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeNoDiagnosis,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        authorize(session);
        return users.list(filters(userId, diagnosisId, type, age, status, tagId, from, to, includeNoDiagnosis, page, size));
    }

    static AdminUsers.Filters filters(UUID userId, UUID diagnosisId, YfsiScoring.Pressure type, String age,
            AdminUsers.Status status, UUID tagId, LocalDate from, LocalDate to, boolean includeNoDiagnosis, int page, int size) {
        var end = to == null ? LocalDate.now(AdminUsers.KST) : to;
        var start = from == null ? end.minusDays(29) : from;
        if (page < 1 || size < 1 || size > 100 || start.isAfter(end)
                || ChronoUnit.DAYS.between(start, end) >= 366
                || (age != null && !AdminUsers.AGE_CODES.contains(age))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return new AdminUsers.Filters(userId, diagnosisId, type == null ? null : type.name(),
                age, status, tagId, start, end, includeNoDiagnosis, page, size);
    }

    @GetMapping("/{id}")
    AdminUsers.Detail detail(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
                             @PathVariable UUID id) {
        authorize(session);
        return users.detail(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/{id}/diagnoses/{diagnosisId}")
    AdminUsers.Diagnosis diagnosis(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
                                   @PathVariable UUID id, @PathVariable UUID diagnosisId) {
        authorize(session);
        return users.diagnosis(id, diagnosisId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void authorize(String session) {
        var identity = auth.identity(session);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!identity.permissions().contains("OPERATOR") && !identity.permissions().contains("PRIVACY")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
