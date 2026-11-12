package kr.finsight.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/experiments")
class AdminExperimentsController {
    private final AdminAuth auth;
    private final AdminExperiments experiments;

    AdminExperimentsController(AdminAuth auth, AdminExperiments experiments) {
        this.auth = auth;
        this.experiments = experiments;
    }

    @GetMapping
    ResponseEntity<AdminExperiments.Listing> list(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(experiments.list(auth.identity(session)));
    }

    @GetMapping("/{id}/results")
    ResponseEntity<AdminExperiments.Report> results(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(experiments.results(auth.identity(session), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AdminExperiments.Item create(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @Valid @RequestBody AdminExperiments.Fields fields) {
        return experiments.create(auth.identity(session), fields);
    }

    @PatchMapping("/{id}")
    AdminExperiments.Item edit(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @PathVariable UUID id, @Valid @RequestBody AdminExperiments.Edit edit) {
        return experiments.edit(auth.identity(session), id, edit);
    }

    @PostMapping("/{id}/transitions")
    AdminExperiments.Item transition(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @PathVariable UUID id, @Valid @RequestBody AdminExperiments.Transition change) {
        return experiments.transition(auth.identity(session), id, change);
    }
}
