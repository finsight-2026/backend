package kr.finsight.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.finsight.content.EditorialPage;
import kr.finsight.diagnosis.YfsiScoring.Pressure;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@RequestMapping("/api/v1/admin/contents")
class AdminNoticesController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    record Draft(AdminNotices.Kind kind, UUID contentId, UUID restoreFrom, AdminNotices.Fields fields) {}
    record Edit(UUID revisionId, Long expectedVersion, AdminNotices.Fields fields) {}

    private final AdminAuth auth;
    private final AdminNotices notices;

    AdminNoticesController(AdminAuth auth, AdminNotices notices) {
        this.auth = auth;
        this.notices = notices;
    }

    @GetMapping
    ResponseEntity<List<AdminNotices.Revision>> list(@CookieValue(name = SESSION, required = false) String session,
            @RequestParam(defaultValue = "NOTICE") AdminNotices.Kind kind,
            @RequestParam(required = false) Pressure type,
            @RequestParam(required = false) AdminNotices.Status status, @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID authorId, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        require(session, "OPERATOR", "SUPER_ADMIN", "ANALYST");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notices.list(kind, status, q, authorId, from, to, type));
    }

    @GetMapping("/insight-sources")
    ResponseEntity<List<AdminNotices.InsightSource>> insightSources(@CookieValue(name = SESSION, required = false) String session) {
        require(session, "OPERATOR", "SUPER_ADMIN", "ANALYST");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notices.insightSources());
    }

    @GetMapping("/page")
    ResponseEntity<EditorialPage<AdminNotices.Revision>> page(
            @CookieValue(name = SESSION, required = false) String session,
            @RequestParam(defaultValue = "NOTICE") AdminNotices.Kind kind,
            @RequestParam(required = false) AdminNotices.Status status, @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID authorId, @RequestParam(required = false) Pressure type,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        require(session, "OPERATOR", "SUPER_ADMIN", "ANALYST");
        var filter = new EditorialPage.Filter(status == null ? null : status.name(), q, authorId, from, to, type);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notices.page(kind, status, filter, page, size));
    }

    @GetMapping("/lookup")
    ResponseEntity<AdminNotices.Revision> lookup(@CookieValue(name = SESSION, required = false) String session,
            @RequestParam AdminNotices.Kind kind, @RequestParam String key, @RequestParam(required = false) Integer revision) {
        require(session, "OPERATOR", "SUPER_ADMIN", "ANALYST");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notices.lookup(kind, key, revision));
    }

    @GetMapping("/{id}")
    AdminNotices.Revision latest(@CookieValue(name = SESSION, required = false) String session, @PathVariable UUID id) {
        require(session, "OPERATOR", "SUPER_ADMIN", "ANALYST");
        return notices.latest(id);
    }

    @GetMapping("/{id}/revisions")
    List<AdminNotices.Revision> revisions(@CookieValue(name = SESSION, required = false) String session, @PathVariable UUID id) {
        require(session, "OPERATOR", "SUPER_ADMIN", "ANALYST");
        return notices.revisions(id);
    }

    @GetMapping("/{id}/preview")
    ResponseEntity<AdminNotices.Preview> preview(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestParam UUID revisionId) {
        require(session, "OPERATOR", "SUPER_ADMIN", "ANALYST");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notices.preview(id, revisionId));
    }

    @PostMapping
    ResponseEntity<AdminNotices.Revision> draft(@CookieValue(name = SESSION, required = false) String session,
            @RequestBody Draft input) {
        var actor = require(session, "OPERATOR");
        if (input.kind() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        return ResponseEntity.status(HttpStatus.CREATED).body(notices.draft(actor, input.kind(), input.contentId(), input.restoreFrom(), input.fields()));
    }

    @PatchMapping("/{id}/draft")
    AdminNotices.Revision edit(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestBody Edit input) {
        var actor = require(session, "OPERATOR");
        if (input.revisionId() == null || input.expectedVersion() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return notices.edit(actor, id, input.revisionId(), input.expectedVersion(), input.fields());
    }

    @PostMapping("/{id}/transitions")
    AdminNotices.Revision transition(@CookieValue(name = SESSION, required = false) String session,
            @PathVariable UUID id, @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody AdminNotices.Change input) {
        if (input.action() == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        var actor = switch (input.action()) {
            case SUBMIT, WITHDRAW -> require(session, "OPERATOR");
            case ARCHIVE -> require(session, "OPERATOR", "SUPER_ADMIN");
            default -> require(session, "SUPER_ADMIN");
        };
        return notices.transition(actor, id, input, key);
    }

    private AdminAuth.Identity require(String session, String... roles) {
        var identity = auth.identity(session);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        for (var role : roles) if (identity.permissions().contains(role)) return identity;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
