package kr.finsight.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin")
class AdminJobsController {
    record DeletionRetry(@NotNull @PositiveOrZero Integer expectedRequeueCount, String reason) {}
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;

    private final AdminAuth auth;
    private final AdminJobs jobs;

    AdminJobsController(AdminAuth auth, AdminJobs jobs) {
        this.auth = auth;
        this.jobs = jobs;
    }

    @GetMapping("/jobs")
    ResponseEntity<AdminJobs.Page> list(@CookieValue(name = SESSION, required = false) String session,
            @RequestParam(required = false) AdminJobs.Kind kind, @RequestParam(required = false) AdminJobs.Status status,
            @RequestParam(required = false) UUID actorId, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return privateResponse(jobs.list(auth.identity(session),
                new AdminJobs.Filter(kind, status, actorId, from, to, page, size), false));
    }

    @GetMapping("/jobs/{id}")
    ResponseEntity<AdminJobs.Job> detail(@CookieValue(name = SESSION, required = false) String session,
                                       @PathVariable UUID id) {
        return privateResponse(jobs.detail(auth.identity(session), id, false));
    }

    @GetMapping("/deletion-requests")
    ResponseEntity<AdminJobs.Page> deletionRequests(@CookieValue(name = SESSION, required = false) String session,
            @RequestParam(required = false) AdminJobs.Status status, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return privateResponse(jobs.list(auth.identity(session),
                new AdminJobs.Filter(null, status, null, from, to, page, size), true));
    }

    @GetMapping("/deletion-requests/{id}")
    ResponseEntity<AdminJobs.Job> deletionDetail(@CookieValue(name = SESSION, required = false) String session,
                                               @PathVariable UUID id) {
        return privateResponse(jobs.detail(auth.identity(session), id, true));
    }

    @PostMapping("/deletion-requests/{id}/retry")
    ResponseEntity<AdminJobs.Job> retryDeletion(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody DeletionRetry request) {
        var job = jobs.requeueDeletion(auth.identity(session), session, id,
                new AdminJobs.Requeue(request.expectedRequeueCount(), request.reason()), key);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(job);
    }

    private static <T> ResponseEntity<T> privateResponse(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
