package kr.finsight.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@RequestMapping("/api/v1/admin/user-bulk")
class AdminBulkTagsController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    private final AdminAuth auth;
    private final AdminBulkTags bulk;

    record Filter(UUID userId, UUID diagnosisId, YfsiScoring.Pressure type, String age, AdminUsers.Status status,
                  UUID tagId, LocalDate from, LocalDate to, Boolean includeNoDiagnosis) {
        AdminUsers.Filters toFilters() {
            return AdminUsersController.filters(userId, diagnosisId, type, age, status, tagId,
                    from, to, Boolean.TRUE.equals(includeNoDiagnosis), 1, 20);
        }
    }
    record PreviewInput(Filter filter, List<UUID> userIds, AdminBulkTags.Action action, List<UUID> tagIds) {}
    record ConfirmInput(@NotNull UUID previewId) {}

    AdminBulkTagsController(AdminAuth auth, AdminBulkTags bulk) {
        this.auth = auth;
        this.bulk = bulk;
    }

    @PostMapping("/preview")
    AdminBulkTags.Preview preview(@CookieValue(name = SESSION, required = false) String session,
            @RequestBody PreviewInput input) {
        var actor = operator(session);
        return bulk.preview(actor, input.filter() == null ? null : input.filter().toFilters(),
                input.userIds(), input.action(), input.tagIds());
    }

    @PostMapping
    ResponseEntity<AdminBulkTags.Job> confirm(@CookieValue(name = SESSION, required = false) String session,
            @Valid @RequestBody ConfirmInput input) {
        return ResponseEntity.accepted().body(bulk.confirm(operator(session), input.previewId()));
    }

    @GetMapping("/{id}")
    ResponseEntity<AdminBulkTags.Job> job(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bulk.job(operator(session).admin().id(), id));
    }

    private AdminAuth.Identity operator(String session) {
        var identity = auth.identity(session);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!identity.permissions().contains("OPERATOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return identity;
    }
}
