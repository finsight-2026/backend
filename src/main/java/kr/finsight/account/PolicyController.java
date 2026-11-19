package kr.finsight.account;

import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
class PolicyController {
    private final PolicyDocuments policies;

    PolicyController(PolicyDocuments policies) {
        this.policies = policies;
    }

    @GetMapping("/api/v1/policies")
    ResponseEntity<PolicyDocuments.Catalog> catalog() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policies.catalog());
    }

    @GetMapping("/api/v1/policies/{id}/{version}")
    ResponseEntity<PolicyDocuments.Detail> detail(@PathVariable String id, @PathVariable String version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policies.detail(id, version));
    }

    @GetMapping("/privacy")
    ResponseEntity<String> privacy() {
        var active = policies.activeVersion().filter(version -> !PolicyDocuments.PREVIEW_VERSION.equals(version));
        if (active.isEmpty()) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body("공개 개인정보 처리방침을 확인할 수 없습니다.");
        }
        var policy = policies.detail(PolicyDocuments.Id.PRIVACY_POLICY.key, active.get());
        return publicPage(policy.document().title(), """
                <p>시행일 %s</p>
                <p class="policy">%s</p>
                <p><a href="/support">FINSIGHT 고객 지원</a></p>
                """.formatted(policy.document().effectiveDate(), HtmlUtils.htmlEscape(policy.body())));
    }

    @GetMapping("/support")
    ResponseEntity<String> support() {
        return publicPage("FINSIGHT 고객 지원", """
                <p>계정, 자가점검, 개인정보에 관한 문의는 핀사이트 2026에 보내 주세요.</p>
                <p><a href="mailto:emfpdlzj@gmail.com">emfpdlzj@gmail.com</a></p>
                <h2>데이터와 계정 관리</h2>
                <p>데이터 삭제는 앱의 MY → 데이터 삭제에서 요청할 수 있습니다.
                계정 탈퇴는 MY → 계정 보안에서 요청할 수 있습니다.</p>
                <p><a href="/privacy">개인정보 처리방침</a></p>
                """);
    }

    private static ResponseEntity<String> publicPage(String title, String content) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8)).body("""
                <!doctype html>
                <html lang="ko">
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>
                body { margin: 0; background: #0f1112; color: #f5f6f6; font: 1rem/1.65 system-ui, sans-serif; }
                main { max-width: 42rem; margin: auto; padding: 2rem 1.25rem; overflow-wrap: anywhere; }
                a { color: #c8ff00; }
                .policy { white-space: pre-wrap; }
                </style>
                <main>
                <h1>%s</h1>
                %s
                </main>
                </html>
                """.formatted(title, title, content));
    }
}
