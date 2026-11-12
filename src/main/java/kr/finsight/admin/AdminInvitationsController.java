package kr.finsight.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/auth")
class AdminInvitationsController {
    record Activate(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token, @NotNull UUID enrollmentId,
                    @NotBlank @Size(min = 12, max = 128) String password,
                    @NotBlank @Pattern(regexp = "[0-9]{6}") String totpCode) {}
    record Activated(List<String> recoveryCodes) {}

    private final AdminInvitations invitations;

    AdminInvitationsController(AdminInvitations invitations) {
        this.invitations = invitations;
    }

    @GetMapping("/invitations/{token}")
    ResponseEntity<AdminInvitations.Preview> preview(@PathVariable String token) {
        return privateResponse(invitations.preview(token));
    }

    @PostMapping("/invitations/{token}/enrollment")
    ResponseEntity<AdminInvitations.Enrollment> enroll(@PathVariable String token) {
        return privateResponse(invitations.begin(token));
    }

    @PostMapping("/activate")
    ResponseEntity<Activated> activate(@Valid @RequestBody Activate request) {
        var result = invitations.activate(request.token(), request.enrollmentId(), request.password(), request.totpCode());
        return switch (result.result()) {
            case ACTIVATED -> privateResponse(new Activated(result.recoveryCodes()));
            case INVALID -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
            case LIMITED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        };
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(body);
    }
}
