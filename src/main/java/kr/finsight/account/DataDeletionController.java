package kr.finsight.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import kr.finsight.content.ContentEvents;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}", exposedHeaders = ContentEvents.EPOCH_HEADER)
@RequestMapping("/api/v1/account")
class DataDeletionController {
    record Request(@NotNull UUID requestId) {}

    private final DataDeletionService deletion;

    DataDeletionController(DataDeletionService deletion) {
        this.deletion = deletion;
    }

    @PostMapping("/data-deletion")
    ResponseEntity<DataDeletionService.Receipt> delete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Request request) {
        var result = deletion.request(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")),
                request.requestId());
        return response(result, "COMPLETED".equals(result.status()) ? HttpStatus.OK : HttpStatus.ACCEPTED);
    }

    @GetMapping("/data-deletion")
    ResponseEntity<DataDeletionService.Receipt> pending(@AuthenticationPrincipal Jwt jwt) {
        return response(deletion.pending(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)), HttpStatus.OK);
    }

    @GetMapping("/data-deletion/{requestId}")
    ResponseEntity<DataDeletionService.Receipt> receipt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId) {
        var result = deletion.receipt(UUID.fromString(jwt.getSubject()), requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return response(result, HttpStatus.OK);
    }

    private ResponseEntity<DataDeletionService.Receipt> response(DataDeletionService.Receipt result, HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header(ContentEvents.EPOCH_HEADER, Long.toString(result.contentEventEpoch())).body(result);
    }
}
