package kr.finsight.comparison;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
class ComparisonController {
    private final ComparisonRead comparisons;

    ComparisonController(ComparisonRead comparisons) {
        this.comparisons = comparisons;
    }

    @GetMapping("/api/v1/comparison")
    ResponseEntity<Map<String, Object>> current(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(comparisons.current(UUID.fromString(jwt.getSubject())));
    }
}
