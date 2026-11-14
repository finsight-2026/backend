package kr.finsight.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
@RequestMapping("/api/v1/auth")
class AppSessionController {
    record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 64) String password) {}
    record RefreshRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {}
    record RevokeRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String revocationToken) {}

    private final AppSessions sessions;

    AppSessionController(AppSessions sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        var result = sessions.login(request.email(), request.password(), http.getRemoteAddr());
        if (result.limited()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해주세요.");
        if (result.withdrawalDueAt() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "WITHDRAWAL_PENDING", "dueAt", result.withdrawalDueAt()));
        }
        if (result.tokens() == null) throw unauthorized();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.tokens());
    }

    @PostMapping("/refresh")
    ResponseEntity<AppSessions.Tokens> refresh(@Valid @RequestBody RefreshRequest request) {
        var tokens = sessions.refresh(request.refreshToken());
        if (tokens == null) throw unauthorized();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal Jwt jwt) {
        sessions.logout(UUID.fromString(jwt.getClaimAsString("sid")), UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/logout/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@Valid @RequestBody RevokeRequest request) {
        sessions.revoke(request.revocationToken());
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 정보를 확인해주세요.");
    }
}
