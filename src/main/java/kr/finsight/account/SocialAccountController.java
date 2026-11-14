package kr.finsight.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/v1")
class SocialAccountController {
    private static final String TOKEN = "[A-Za-z0-9_-]{43}";
    record Start(@NotNull SocialAccounts.Provider provider, @NotNull SocialAuthorization.Purpose purpose,
                 Boolean confirmed) {}
    record Finish(@NotBlank @Pattern(regexp = TOKEN) String state, @NotBlank @Pattern(regexp = TOKEN) String verifier) {}
    record Link(@NotBlank @Pattern(regexp = TOKEN) String onboardingToken) {}
    record Mail(@NotBlank @Pattern(regexp = TOKEN) String onboardingToken,
                @NotBlank @Email @Size(max = 254) String email) {}
    record Outcome(String status, AppSessions.Tokens tokens, String onboardingToken,
                   String verifiedEmail, OffsetDateTime withdrawalDueAt) {}

    private final SocialAuthorization authorization;
    private final SocialAccounts accounts;

    SocialAccountController(SocialAuthorization authorization, SocialAccounts accounts) {
        this.authorization = authorization;
        this.accounts = accounts;
    }

    @GetMapping("/auth/social/providers")
    ResponseEntity<List<SocialAccounts.Provider>> providers() {
        return privateResponse(authorization.providers());
    }

    @PostMapping("/auth/social/start")
    ResponseEntity<SocialAuthorization.Started> start(@Valid @RequestBody Start body, HttpServletRequest http) {
        if (body.purpose() != SocialAuthorization.Purpose.LOGIN
                && !(body.purpose() == SocialAuthorization.Purpose.CANCEL_WITHDRAWAL && Boolean.TRUE.equals(body.confirmed()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return privateResponse(authorization.start(body.provider(), body.purpose(), null, http.getRemoteAddr()));
    }

    @PostMapping("/account/social/start")
    ResponseEntity<SocialAuthorization.Started> startAccount(@Valid @RequestBody Start body,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        if (body.purpose() != SocialAuthorization.Purpose.LINK && body.purpose() != SocialAuthorization.Purpose.REAUTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return privateResponse(authorization.start(body.provider(), body.purpose(), session(jwt), http.getRemoteAddr()));
    }

    @GetMapping("/auth/social/callback/{provider:naver|kakao}")
    ResponseEntity<Void> redirect(@PathVariable String provider, @RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error) {
        return callback(SocialAccounts.Provider.valueOf(provider.toUpperCase(Locale.ROOT)), state, code, error);
    }

    @PostMapping(value = "/auth/social/callback/apple", consumes = "application/x-www-form-urlencoded")
    ResponseEntity<Void> apple(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code, @RequestParam(required = false) String error) {
        return callback(SocialAccounts.Provider.APPLE, state, code, error);
    }

    @PostMapping("/auth/social/finish")
    ResponseEntity<Outcome> finish(@Valid @RequestBody Finish body) {
        var verified = authorization.take(body.state(), body.verifier(), null);
        if (verified.identity() == null) return unavailable(verified);
        if (verified.purpose() == SocialAuthorization.Purpose.CANCEL_WITHDRAWAL) {
            return result(accounts.cancelWithdrawal(verified.identity()) ? "WITHDRAWAL_CANCELED" : "FAILED");
        }
        var started = accounts.signIn(verified.identity());
        if (started.login() == null) return privateResponse(new Outcome("ONBOARDING", null,
                started.onboardingToken(), started.verifiedEmail(), null));
        if (started.login().withdrawalDueAt() != null) return privateResponse(new Outcome("WITHDRAWAL_PENDING",
                null, null, null, started.login().withdrawalDueAt()));
        return started.login().tokens() == null ? result("FAILED")
                : privateResponse(new Outcome("AUTHENTICATED", started.login().tokens(), null, null, null));
    }

    @PostMapping("/auth/social/email")
    ResponseEntity<SignupService.Window> sendMail(@Valid @RequestBody Mail body, HttpServletRequest http) {
        return privateResponse(accounts.sendEmail(body.onboardingToken(), body.email(), http.getRemoteAddr()));
    }

    @PostMapping("/auth/social/register")
    ResponseEntity<Outcome> register(@RequestBody SocialAccounts.Registration body) {
        var response = accounts.register(body);
        return privateResponse(new Outcome(response.result().name(), response.tokens(), null, null, null));
    }

    @PostMapping("/account/social/finish")
    ResponseEntity<Outcome> finishAccount(@Valid @RequestBody Finish body, @AuthenticationPrincipal Jwt jwt) {
        var account = session(jwt);
        var verified = authorization.take(body.state(), body.verifier(), account);
        if (verified.identity() == null) return unavailable(verified);
        if (verified.purpose() == SocialAuthorization.Purpose.REAUTH) {
            return result(accounts.reauthenticate(account.user(), account.session(), verified.identity())
                    ? "REAUTHENTICATED" : "FAILED");
        }
        return result(accounts.link(account.user(), account.session(), verified.identity()).name());
    }

    @PostMapping("/account/social/link")
    ResponseEntity<Outcome> link(@Valid @RequestBody Link body, @AuthenticationPrincipal Jwt jwt) {
        var account = session(jwt);
        return result(accounts.linkSameEmail(account.user(), account.session(), body.onboardingToken()).name());
    }

    @DeleteMapping("/account/social/{provider}")
    ResponseEntity<Outcome> unlink(@PathVariable SocialAccounts.Provider provider, @AuthenticationPrincipal Jwt jwt) {
        var account = session(jwt);
        return result(accounts.unlink(account.user(), account.session(), provider).name());
    }

    private ResponseEntity<Void> callback(SocialAccounts.Provider provider, String state, String code, String error) {
        var location = authorization.callback(provider, state, code, error);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(location).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").build();
    }

    private static SocialAuthorization.Session session(Jwt jwt) {
        return new SocialAuthorization.Session(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")));
    }

    private static ResponseEntity<Outcome> unavailable(SocialAuthorization.Verified verified) {
        return result(verified.canceled() ? "CANCELED" : "FAILED");
    }

    private static ResponseEntity<Outcome> result(String status) {
        return privateResponse(new Outcome(status, null, null, null, null));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
