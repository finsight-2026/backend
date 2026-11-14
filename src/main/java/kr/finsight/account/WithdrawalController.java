package kr.finsight.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
@RequestMapping("/api/v1/account")
class WithdrawalController {
    record Reauth(@NotBlank @Size(max = 64) String password) {}
    record Cancel(@NotBlank @Email @Size(max = 254) String email,
                  @NotBlank @Size(max = 64) String password, @AssertTrue boolean confirmed) {}
    record Receipt(OffsetDateTime dueAt) {}

    private final WithdrawalService withdrawals;

    WithdrawalController(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    @PostMapping("/reauth")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reauth(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Reauth request, HttpServletRequest http) {
        var result = withdrawals.reauthenticate(user(jwt), session(jwt), request.password(), http.getRemoteAddr());
        if (result.limited()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        if (result.userId() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @PostMapping("/withdrawal")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Receipt request(@AuthenticationPrincipal Jwt jwt) {
        var due = withdrawals.request(user(jwt), session(jwt));
        if (due == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return new Receipt(due);
    }

    @PostMapping("/withdrawal/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@Valid @RequestBody Cancel request, HttpServletRequest http) {
        requireVerified(withdrawals.cancel(request.email(), request.password(), http.getRemoteAddr()));
    }

    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static UUID session(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("sid")); }

    private static void requireVerified(AppSessions.Authentication result) {
        if (result.limited()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        if (result.userId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
