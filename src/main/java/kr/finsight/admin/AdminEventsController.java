package kr.finsight.admin;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/events")
class AdminEventsController {
    private final AdminAuth auth;
    private final AdminEvents events;

    AdminEventsController(AdminAuth auth, AdminEvents events) {
        this.auth = auth;
        this.events = events;
    }

    @GetMapping
    ResponseEntity<AdminEvents.Page> list(
            @CookieValue(name = AdminAuthController.SESSION_COOKIE, required = false) String session,
            @RequestParam(required = false) UUID eventId, @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID attemptId, @RequestParam(required = false) UUID journeyId,
            @RequestParam(required = false) AdminEvents.Type type, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(events.list(auth.identity(session),
                new AdminEvents.Filter(eventId, userId, attemptId, journeyId, type, from, to, page, size)));
    }
}
