package kr.finsight.admin;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/settings")
class AdminOperatingSettingsController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    private final AdminAuth auth;
    private final AdminOperatingSettings settings;

    AdminOperatingSettingsController(AdminAuth auth, AdminOperatingSettings settings) {
        this.auth = auth;
        this.settings = settings;
    }

    @GetMapping
    ResponseEntity<AdminOperatingSettings.Settings> read(@CookieValue(name = SESSION, required = false) String session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.read(auth.identity(session)));
    }

    @PatchMapping
    ResponseEntity<AdminOperatingSettings.Settings> update(@CookieValue(name = SESSION, required = false) String session,
            @RequestBody AdminOperatingSettings.Update request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.update(auth.identity(session), session, request));
    }
}
