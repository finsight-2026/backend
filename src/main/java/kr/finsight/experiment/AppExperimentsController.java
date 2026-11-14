package kr.finsight.experiment;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
@RequestMapping("/api/v1/experiments")
class AppExperimentsController {
    private final ExperimentRuntime experiments;

    AppExperimentsController(ExperimentRuntime experiments) { this.experiments = experiments; }

    @PostMapping("/assign")
    ResponseEntity<ExperimentRuntime.Assignment> assign(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ExperimentRuntime.Request input) {
        return experiments.assign(UUID.fromString(jwt.getSubject()), input)
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @PostMapping("/{id}/exposure")
    ResponseEntity<Void> expose(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @Valid @RequestBody ExperimentRuntime.Exposure input) {
        experiments.expose(UUID.fromString(jwt.getSubject()), id, input);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
