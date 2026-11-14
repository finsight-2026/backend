package kr.finsight.account;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
@RequestMapping("/api/v1/account/export")
class AppDataExportController {
    private final AppDataExports exports;

    AppDataExportController(AppDataExports exports) {
        this.exports = exports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    AppDataExports.Job request(@AuthenticationPrincipal Jwt jwt) {
        return exports.request(user(jwt), UUID.fromString(jwt.getClaimAsString("sid")));
    }

    @GetMapping
    AppDataExports.Job latest(@AuthenticationPrincipal Jwt jwt) {
        return exports.latest(user(jwt));
    }

    @GetMapping("/{id}")
    ResponseEntity<AppDataExports.Job> completed(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(exports.completed(user(jwt), id));
    }

    @PostMapping("/{id}/link")
    AppDataExports.Link link(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return exports.link(user(jwt), id);
    }

    @GetMapping("/download/{id}")
    ResponseEntity<byte[]> download(@PathVariable UUID id, @RequestParam long expires, @RequestParam String signature) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("finsight-data.zip").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(exports.download(id, expires, signature));
    }

    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
