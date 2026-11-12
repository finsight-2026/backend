package kr.finsight.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/admins")
class AdminAccountsController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    record Reason(String reason) {}

    private final AdminAuth auth;
    private final AdminAccounts accounts;

    AdminAccountsController(AdminAuth auth, AdminAccounts accounts) {
        this.auth = auth;
        this.accounts = accounts;
    }

    @GetMapping
    ResponseEntity<List<AdminAccounts.Account>> list(@CookieValue(name = SESSION, required = false) String session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.list(auth.identity(session)));
    }

    @PostMapping("/invitations")
    ResponseEntity<AdminInvitations.Receipt> invite(@CookieValue(name = SESSION, required = false) String session,
            @RequestBody AdminAccounts.Invite request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(accounts.invite(auth.identity(session), session, request));
    }

    @PatchMapping("/{id}")
    ResponseEntity<AdminAccounts.Account> change(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody AdminAccounts.Change request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.update(auth.identity(session), session, id, request));
    }

    @PostMapping("/{id}/resend-invitation")
    ResponseEntity<AdminInvitations.Receipt> resend(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody Reason request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(accounts.resend(auth.identity(session), session, id, request.reason()));
    }

    @PostMapping("/{id}/mfa-reset")
    ResponseEntity<AdminInvitations.Receipt> reset(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody Reason request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(accounts.resetMfa(auth.identity(session), session, id, request.reason()));
    }
}
