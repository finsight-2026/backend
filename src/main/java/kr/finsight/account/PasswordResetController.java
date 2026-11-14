package kr.finsight.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
@RequestMapping("/api/v1/auth/password-reset")
class PasswordResetController {
    record Request(@NotBlank @Email @Size(max = 254) String email) {}
    record Complete(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
                    @NotBlank @Size(min = 8, max = 64)
                    @Pattern(regexp = "(?U)(?=.*[A-Za-z])(?=.*[0-9])[^\\s]+") String password) {}

    private final PasswordReset reset;

    PasswordResetController(PasswordReset reset) {
        this.reset = reset;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void request(@Valid @RequestBody Request body, HttpServletRequest request) {
        if (!reset.request(body.email(), request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @PostMapping("/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void complete(@Valid @RequestBody Complete body) {
        if (!reset.complete(body.token(), body.password())) throw new ResponseStatusException(HttpStatus.GONE);
    }
}
