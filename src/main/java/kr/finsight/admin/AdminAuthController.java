package kr.finsight.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/auth")
class AdminAuthController {
    static final String SESSION_COOKIE = "__Secure-FINSIGHT_ADMIN";
    record Credentials(@NotBlank @Email @Size(max = 254) String email,
                       @NotBlank @Size(max = 128) String password) {}
    record Mfa(@NotNull UUID challengeId, @Pattern(regexp = "[0-9]{6}") String code,
               @Pattern(regexp = "[A-Z2-7]{16}") String recoveryCode) {}

    record Reauthenticate(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}

    private final AdminAuth auth;

    AdminAuthController(AdminAuth auth) {
        this.auth = auth;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    AdminAuth.Challenge login(@Valid @RequestBody Credentials request, HttpServletRequest http) {
        var result = auth.login(request.email(), request.password(), http.getRemoteAddr());
        if (result.limited()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        if (result.challenge() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return result.challenge();
    }

    @PostMapping("/mfa")
    ResponseEntity<AdminAuth.Identity> verify(@Valid @RequestBody Mfa request) {
        if ((request.code() == null) == (request.recoveryCode() == null)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var result = auth.verify(request.challengeId(), request.code(), request.recoveryCode());
        if (result.limited()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        if (result.token() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                cookie(result.token(), Duration.ofHours(8)).toString()).body(result.identity());
    }

    @PostMapping("/reauth")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reauthenticate(@CookieValue(name = SESSION_COOKIE, required = false) String token,
                        @Valid @RequestBody Reauthenticate request) {
        switch (auth.reauthenticate(token, request.code())) {
            case VERIFIED -> { }
            case INVALID -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
            case LIMITED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            case SESSION_EXPIRED -> throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    @GetMapping("/me")
    AdminAuth.Identity me(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
        var identity = auth.identity(token);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return identity;
    }

    @PostMapping("/activity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void activity(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
        if (!auth.recordInteraction(token)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
        auth.logout(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private static ResponseCookie cookie(String token, Duration duration) {
        return ResponseCookie.from(SESSION_COOKIE, token).path("/api/v1/admin").maxAge(duration)
                .secure(true).httpOnly(true).sameSite("Strict").build();
    }
}
