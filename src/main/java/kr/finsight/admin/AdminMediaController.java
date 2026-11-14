package kr.finsight.admin;

import java.util.UUID;
import kr.finsight.media.MediaStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequestMapping("/api/v1/admin/media")
class AdminMediaController {
    private static final String SESSION = AdminAuthController.SESSION_COOKIE;
    private final AdminAuth auth;
    private final MediaStore store;

    AdminMediaController(AdminAuth auth, MediaStore store) {
        this.auth = auth;
        this.store = store;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<MediaStore.Asset> upload(@CookieValue(name = SESSION, required = false) String session,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(store.upload(require(session, false), file));
    }

    @GetMapping("/{id}")
    ResponseEntity<MediaStore.Asset> detail(@CookieValue(name = SESSION, required = false) String session, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.details(require(session, true), id));
    }

    @GetMapping("/{id}/file")
    ResponseEntity<byte[]> file(@CookieValue(name = SESSION, required = false) String session, @PathVariable UUID id) {
        var file = store.adminFile(require(session, true), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.mime()))
                .header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    private UUID require(String session, boolean read) {
        var actor = auth.identity(session);
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!actor.permissions().contains("OPERATOR") && !(read && actor.permissions().contains("SUPER_ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return actor.admin().id();
    }
}
