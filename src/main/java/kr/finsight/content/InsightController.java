package kr.finsight.content;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}", exposedHeaders = ContentEvents.EPOCH_HEADER)
@RequestMapping("/api/v1/insights")
class InsightController {
    record Helpful(@NotNull @Positive Integer revision, @NotNull Boolean helpful) {}

    private final InsightService insights;

    InsightController(InsightService insights) { this.insights = insights; }

    @GetMapping
    ResponseEntity<InsightService.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) InsightService.Topic topic,
            @RequestParam(required = false) OffsetDateTime before, @RequestParam(required = false) UUID beforeId) {
        return privateResponse(insights.list(user(jwt), topic, before, beforeId));
    }

    @GetMapping("/{id}")
    ResponseEntity<InsightService.Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return privateResponse(insights.detail(user(jwt), id));
    }

    @PutMapping("/{id}/helpful")
    ResponseEntity<InsightService.Detail> helpful(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody Helpful input) {
        return privateResponse(insights.helpful(user(jwt), id, input.revision(), input.helpful()));
    }

    @PostMapping("/{id}/events")
    ResponseEntity<Void> event(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ContentEvents.Report event) {
        var epoch = insights.record(user(jwt), id, event);
        return ResponseEntity.noContent().header(ContentEvents.EPOCH_HEADER, Long.toString(epoch)).build();
    }

    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
