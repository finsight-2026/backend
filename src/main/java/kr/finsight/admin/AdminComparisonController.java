package kr.finsight.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.comparison.ComparisonBuild;
import kr.finsight.comparison.ComparisonPublishing;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@RequestMapping("/api/v1/admin/comparison-snapshots")
class AdminComparisonController {
    record BuildRequest(@NotNull YfsiScoring.Pressure type, LocalDate from, LocalDate to,
                        @NotBlank String formulaVersion) {}
    record Minimum(Integer minimum, @NotNull Long expectedVersion, @NotBlank String reason) {}

    private final AdminAuth auth;
    private final ComparisonBuild comparison;
    private final ComparisonPublishing publishing;
    private final AdminMutationJournal journal;

    AdminComparisonController(AdminAuth auth, ComparisonBuild comparison, ComparisonPublishing publishing, AdminMutationJournal journal) {
        this.auth = auth;
        this.comparison = comparison;
        this.publishing = publishing;
        this.journal = journal;
    }

    @GetMapping
    List<ComparisonBuild.Snapshot> list(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @RequestParam(required = false) YfsiScoring.Pressure type,
            @RequestParam(required = false) ComparisonBuild.Status status) {
        require(session, "OPERATOR", "SUPER_ADMIN");
        return comparison.list(type, status);
    }

    @GetMapping("/{id}")
    ComparisonBuild.Snapshot get(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
                                 @PathVariable UUID id) {
        require(session, "OPERATOR", "SUPER_ADMIN");
        return comparison.get(id);
    }

    @GetMapping("/{id}/preview")
    ComparisonPublishing.Preview preview(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
                                         @PathVariable UUID id) {
        require(session, "OPERATOR", "SUPER_ADMIN");
        return publishing.preview(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ComparisonBuild.Snapshot build(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
                                   @Valid @RequestBody BuildRequest request) {
        var actor = require(session, "OPERATOR");
        return comparison.request(actor.admin().id(), request.type(), request.from(), request.to(), request.formulaVersion());
    }

    @PostMapping("/{id}/transitions")
    ComparisonBuild.Snapshot transition(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @PathVariable UUID id, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody ComparisonPublishing.Change transition) {
        if (transition.action() == null || transition.expectedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (transition.action() == ComparisonPublishing.Action.SUBMIT) {
            require(session, "OPERATOR");
            return comparison.submit(id, transition.expectedVersion());
        }
        var actor = require(session, "SUPER_ADMIN");
        return journal.once(actor, "/comparison-snapshots/" + id + "/transitions", key, transition,
                ComparisonBuild.Snapshot.class, () -> publishing.transition(actor.admin().id(), id, transition),
                snapshot -> journal.append(actor, "COMPARISON_" + transition.action(), "COMPARISON_SNAPSHOT",
                        id.toString(), Map.of("status", snapshot.status().name(), "version", snapshot.version(),
                                "reasonProvided", transition.reason() != null && !transition.reason().isBlank())));
    }

    @GetMapping("/settings")
    List<ComparisonPublishing.Settings> settings(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session) {
        require(session, "OPERATOR", "SUPER_ADMIN");
        return publishing.settings();
    }

    @GetMapping("/publications")
    List<ComparisonPublishing.Publication> publications(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session) {
        require(session, "OPERATOR", "SUPER_ADMIN");
        return publishing.publications();
    }

    @PatchMapping("/settings/{type}")
    ComparisonPublishing.Settings minimum(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @PathVariable YfsiScoring.Pressure type, @Valid @RequestBody Minimum request) {
        var actor = require(session, "SUPER_ADMIN");
        return journal.record(actor, "COMPARISON_MINIMUM_CHANGED", "COMPARISON_DATASET", type.name(), () -> {
            auth.lockVerifiedSupervisor(actor, session);
            return publishing.updateMinimum(type, request.minimum(), request.expectedVersion(), request.reason());
        }, settings -> Map.of("version", settings.version(), "minimumConfigured", settings.minimum() != null));
    }

    private AdminAuth.Identity require(String session, String... roles) {
        var identity = auth.identity(session);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        for (String role : roles) {
            if (identity.permissions().contains(role)) return identity;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
