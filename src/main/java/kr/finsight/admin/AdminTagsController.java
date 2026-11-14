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
@RequestMapping("/api/v1/admin")
class AdminTagsController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    private final AdminAuth auth;
    private final AdminTags tags;

    record TagInput(String name, Long expectedVersion) {}
    record TagSelection(List<UUID> tagIds, List<UUID> expectedTagIds) {}

    AdminTagsController(AdminAuth auth, AdminTags tags) {
        this.auth = auth;
        this.tags = tags;
    }

    @GetMapping("/tags")
    ResponseEntity<List<AdminTags.Tag>> list(@CookieValue(name = SESSION, required = false) String session) {
        operator(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tags.list());
    }

    @PostMapping("/tags")
    ResponseEntity<AdminTags.Tag> create(@CookieValue(name = SESSION, required = false) String session,
            @RequestBody TagInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tags.create(operator(session), input.name()));
    }

    @PatchMapping("/tags/{id}")
    AdminTags.Tag rename(@CookieValue(name = SESSION, required = false) String session, @PathVariable UUID id,
            @RequestBody TagInput input) {
        var actor = operator(session);
        if (input.expectedVersion() == null || input.expectedVersion() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return tags.rename(actor, id, input.name(), input.expectedVersion());
    }

    @GetMapping("/users/{user}/tags")
    ResponseEntity<AdminTags.Selection> selection(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID user) {
        operator(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tags.selection(user));
    }

    @PutMapping("/users/{user}/tags")
    AdminTags.Selection replace(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID user, @RequestBody TagSelection input) {
        return tags.replace(operator(session), user, input.tagIds(), input.expectedTagIds());
    }

    private AdminAuth.Identity operator(String session) {
        var identity = auth.identity(session);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!identity.permissions().contains("OPERATOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return identity;
    }
}
