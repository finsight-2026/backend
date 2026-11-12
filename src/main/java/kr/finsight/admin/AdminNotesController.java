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
@RequestMapping("/api/v1/admin/users/{user}/notes")
class AdminNotesController {
    private final AdminAuth auth;
    private final AdminNotes notes;

    AdminNotesController(AdminAuth auth, AdminNotes notes) {
        this.auth = auth;
        this.notes = notes;
    }

    @GetMapping
    ResponseEntity<List<AdminNotes.Note>> list(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @PathVariable UUID user) {
        authorize(session, false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notes.list(user));
    }

    @PostMapping
    ResponseEntity<AdminNotes.Note> create(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @PathVariable UUID user, @RequestBody AdminNotes.Create input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var actor = authorize(session, false);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(notes.create(actor, user, input, key));
    }

    @PatchMapping("/{id}")
    ResponseEntity<AdminNotes.Note> edit(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @PathVariable UUID user, @PathVariable UUID id, @RequestBody AdminNotes.Edit input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notes.edit(authorize(session, true), user, id, input));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @PathVariable UUID user, @PathVariable UUID id, @RequestBody AdminNotes.Delete input) {
        notes.delete(authorize(session, true), user, id, input);
    }

    private AdminAuth.Identity authorize(String session, boolean supervisorAllowed) {
        var identity = auth.identity(session);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!identity.permissions().contains("OPERATOR")
                && !(supervisorAllowed && identity.permissions().contains("SUPER_ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return identity;
    }
}
