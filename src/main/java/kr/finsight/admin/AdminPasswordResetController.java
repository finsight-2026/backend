package kr.finsight.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/auth")
class AdminPasswordResetController {
    record Request(@NotBlank @Email @Size(max = 254) String email) {}
    record Reset(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
                 @NotBlank @Size(min = 12, max = 128) String newPassword) {}

    private final AdminPasswordReset resets;

    AdminPasswordResetController(AdminPasswordReset resets) {
        this.resets = resets;
    }

    @PostMapping("/reset-request")
    ResponseEntity<Void> request(@Valid @RequestBody Request input, HttpServletRequest http) {
        resets.request(input.email(), http.getRemoteAddr());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/reset")
    ResponseEntity<Void> reset(@Valid @RequestBody Reset input) {
        if (!resets.complete(input.token(), input.newPassword())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
