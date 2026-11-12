package kr.finsight.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@RequestMapping("/api/v1/admin/users/{user}")
class AdminPiiController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    record Request(String reason, List<AdminPii.Scope> scope) {}

    private final AdminAuth auth;
    private final AdminPii pii;

    AdminPiiController(AdminAuth auth, AdminPii pii) {
        this.auth = auth;
        this.pii = pii;
    }

    @PostMapping("/pii-grants")
    ResponseEntity<AdminPii.Grant> grant(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID user, @RequestBody Request input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(pii.issue(privacy(session), session, user, input.reason(), input.scope()));
    }

    @GetMapping("/pii")
    ResponseEntity<AdminPii.Data> read(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID user, @RequestParam UUID grantId, @RequestParam(required = false) UUID diagnosisId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(pii.read(privacy(session), session, user, grantId, diagnosisId));
    }

    @DeleteMapping("/pii-grants/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@CookieValue(name = SESSION, required = false) String session, @PathVariable UUID user,
            @PathVariable UUID grantId) {
        pii.revoke(privacy(session), session, user, grantId);
    }

    private AdminAuth.Identity privacy(String session) {
        var identity = auth.identity(session);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!identity.permissions().contains("PRIVACY")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return identity;
    }
}
