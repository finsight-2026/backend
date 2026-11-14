package kr.finsight.admin;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/audit-logs")
class AdminAuditController {
    private final AdminAuth auth;
    private final AdminMutationJournal journal;

    AdminAuditController(AdminAuth auth, AdminMutationJournal journal) {
        this.auth = auth;
        this.journal = journal;
    }

    @GetMapping
    ResponseEntity<AdminMutationJournal.Page> list(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String token,
            @RequestParam(required = false) UUID actorId, @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetId, @RequestParam(required = false) String result,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        var identity = auth.identity(token);
        if (identity == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!identity.permissions().contains("SUPER_ADMIN") && !identity.permissions().contains("PRIVACY")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var end = to == null ? LocalDate.now(AdminUsers.KST) : to;
        var start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || end.isAfter(start.plusDays(365)) || page < 1 || size < 1 || size > 100
                || (reason != null && reason.codePointCount(0, reason.length()) > 500)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(journal.list(new AdminMutationJournal.Filter(actorId, action, targetId, result, reason, start, end, page, size)));
    }
}
