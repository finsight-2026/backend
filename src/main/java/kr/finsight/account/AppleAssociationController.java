package kr.finsight.account;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local")
@RestController
class AppleAssociationController {
    private final String appId;

    AppleAssociationController(@Value("${FINSIGHT_IOS_APP_ID:}") String appId) {
        if (!appId.isEmpty() && !appId.matches("[A-Z0-9]{10}\\.kr\\.finsight\\.finsight")) {
            throw new IllegalArgumentException("Apple 앱 식별자 형식을 확인해주세요.");
        }
        this.appId = appId;
    }

    @GetMapping(path = "/.well-known/apple-app-site-association", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> association() {
        if (appId.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("applinks", Map.of("details", List.of(Map.of(
                "appIDs", List.of(appId), "components", List.of(Map.of("/", "/password-reset")))))));
    }
}
