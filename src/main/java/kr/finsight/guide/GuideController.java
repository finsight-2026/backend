package kr.finsight.guide;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import kr.finsight.content.ContentEvents;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}", exposedHeaders = ContentEvents.EPOCH_HEADER)
@RequestMapping("/api/v1/guides")
class GuideController {
    record Completion(@NotNull Boolean completed, @Min(1) Integer contentRevision) {}

    private final GuideService guides;

    GuideController(GuideService guides) { this.guides = guides; }

    @GetMapping
    GuideService.Catalog list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID attemptId) {
        return guides.list(user(jwt), attemptId);
    }

    @GetMapping("/widget")
    ResponseEntity<GuideService.WidgetAction> widget(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(guides.widgetAction(user(jwt)));
    }

    @PostMapping("/{id}/events")
    ResponseEntity<Void> impression(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam UUID attemptId,
                                    @Valid @RequestBody ContentEvents.Report event) {
        var epoch = guides.impression(user(jwt), attemptId, id, event);
        return ResponseEntity.noContent().header(ContentEvents.EPOCH_HEADER, Long.toString(epoch)).build();
    }

    @PostMapping("/{id}/view")
    GuideService.Detail view(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam UUID attemptId) {
        return guides.view(user(jwt), attemptId, id);
    }

    @PutMapping("/{id}/completion")
    GuideService.Detail complete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam UUID attemptId,
                                 @Valid @RequestBody Completion completion) {
        return guides.complete(user(jwt), attemptId, id, completion.completed(), completion.contentRevision());
    }

    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
