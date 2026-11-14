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
@RequestMapping("/api/v1/admin/segments")
class AdminSegmentsController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    private final AdminAuth auth;
    private final AdminSegments segments;
    record Update(AdminSegments.Fields fields, Long expectedVersion) {}
    record Archive(Long expectedVersion) {}

    AdminSegmentsController(AdminAuth auth, AdminSegments segments) {
        this.auth = auth;
        this.segments = segments;
    }

    @GetMapping
    ResponseEntity<List<AdminSegments.Segment>> list(@CookieValue(name = SESSION, required = false) String session) {
        operator(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(segments.list());
    }

    @PostMapping
    ResponseEntity<AdminSegments.Segment> create(@CookieValue(name = SESSION, required = false) String session,
            @RequestBody AdminSegments.Fields input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(segments.create(operator(session), input));
    }

    @PatchMapping("/{id}")
    AdminSegments.Segment update(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody Update input) {
        var actor = operator(session);
        return segments.update(actor, id, input.fields(), version(input.expectedVersion()));
    }

    @PostMapping("/{id}/preview")
    ResponseEntity<AdminSegments.Preview> preview(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id) {
        operator(session);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(segments.preview(id));
    }

    @PostMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody Archive input) {
        segments.archive(operator(session), id, version(input.expectedVersion()));
    }

    private static long version(Long value) {
        if (value == null || value < 0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        return value;
    }

    private AdminAuth.Identity operator(String session) {
        var identity = auth.identity(session);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!identity.permissions().contains("OPERATOR")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return identity;
    }
}
