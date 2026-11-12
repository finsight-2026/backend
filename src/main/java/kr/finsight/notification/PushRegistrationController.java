package kr.finsight.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Profile("local")
@RestController
@RequestMapping("/api/v1/notifications/device")
class PushRegistrationController {
    record Registration(@NotNull PushRegistrations.Provider provider,
                        @NotNull @Pattern(regexp = "[A-Za-z0-9_:.=-]{20,4096}") String token) {}

    private final PushRegistrations registrations;

    PushRegistrationController(PushRegistrations registrations) {
        this.registrations = registrations;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid Registration input) {
        registrations.put(UUID.fromString(jwt.getSubject()), session(jwt), input.provider(), input.token());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unregister(@AuthenticationPrincipal Jwt jwt) {
        registrations.remove(UUID.fromString(jwt.getSubject()), session(jwt));
    }

    private static UUID session(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("sid"));
    }
}
