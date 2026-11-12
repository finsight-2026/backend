package kr.finsight.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Profile("local")
@RestController
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
@RequestMapping("/api/v1/notifications")
class AppNotificationController {
    private static final Set<String> FILTERS = Set.of("ALL", "DIAGNOSIS", "GUIDE", "ECONOMY");
    private static final String RETAINED = "created_at > now() - interval '90 days'";
    private static final int PAGE_SIZE = 20;

    record Preferences(boolean enabled, boolean periodic, boolean incomplete, boolean guide, boolean economy) {}
    enum Setting {
        ENABLED("enabled"), PERIODIC("periodic"), INCOMPLETE("incomplete"), GUIDE("guide"), ECONOMY("economy");

        final String column;

        Setting(String column) {
            this.column = column;
        }
    }
    record PreferenceChange(Setting setting, Boolean enabled) {}
    record Item(UUID id, String kind, String title, String body, OffsetDateTime createdAt, boolean read) {}
    record Page(List<Item> items, String nextCursor, boolean unread) {}
    record Cursor(OffsetDateTime at, UUID id) {
        static Cursor parse(String raw) {
            try {
                var parts = raw.split(",", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                return new Cursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (IllegalArgumentException | DateTimeParseException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
    }

    private final JdbcClient db;

    AppNotificationController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping("/preferences")
    ResponseEntity<Preferences> preferences(@AuthenticationPrincipal Jwt jwt) {
        return privateResponse(readPreferences(user(jwt)));
    }

    @PutMapping("/preferences")
    @Transactional
    ResponseEntity<Preferences> preference(@AuthenticationPrincipal Jwt jwt, @RequestBody PreferenceChange input) {
        if (input.setting() == null || input.enabled() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var id = lockActive(jwt);
        var column = input.setting().column;
        db.sql("""
                INSERT INTO app_notification_preference (user_id,%1$s) VALUES (:user,:value)
                ON CONFLICT (user_id) DO UPDATE SET %1$s=excluded.%1$s,updated_at=clock_timestamp()
                """.formatted(column)).param("user", id).param("value", input.enabled()).update();
        return privateResponse(readPreferences(id));
    }

    @GetMapping
    ResponseEntity<Page> inbox(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "ALL") String filter, @RequestParam(required = false) String before) {
        if (!FILTERS.contains(filter)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var cursor = before == null ? null : Cursor.parse(before);
        var id = user(jwt);
        var query = db.sql("""
                SELECT id,kind,title,body,created_at,read_at IS NOT NULL FROM app_notification
                WHERE user_id=:user AND %s
                AND (:filter='ALL' OR kind=:filter OR (:filter='DIAGNOSIS' AND kind IN ('PERIODIC','INCOMPLETE')))
                %s ORDER BY created_at DESC,id DESC LIMIT :limit
                """.formatted(RETAINED, cursor == null ? "" : "AND (created_at,id)<(:at,:id)"))
                .param("user", id).param("filter", filter).param("limit", PAGE_SIZE + 1);
        if (cursor != null) query.param("at", cursor.at()).param("id", cursor.id());
        var items = query.query((row, index) -> new Item(row.getObject(1, UUID.class), row.getString(2),
                row.getString(3), row.getString(4), row.getObject(5, OffsetDateTime.class), row.getBoolean(6))).list();
        String next = null;
        if (items.size() > PAGE_SIZE) {
            items = items.subList(0, PAGE_SIZE);
            var last = items.getLast();
            next = last.createdAt() + "," + last.id();
        }
        var unread = db.sql("SELECT EXISTS(SELECT 1 FROM app_notification WHERE user_id=:user AND read_at IS NULL AND " + RETAINED + ")")
                .param("user", id).query(Boolean.class).single();
        return privateResponse(new Page(items, next, unread));
    }

    record Notice(String title, String summary, String body) {}
    record Target(String kind, String resource, UUID attemptId, boolean available,
                  @JsonInclude(JsonInclude.Include.NON_NULL) Notice notice,
                  @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {
        Target(String kind, String resource, UUID attemptId, boolean available) {
            this(kind, resource, attemptId, available, null, null);
        }
    }
    record Event(String kind, String key) {}

    @GetMapping("/{id}/target")
    ResponseEntity<Target> target(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var user = user(jwt);
        var event = db.sql("""
                SELECT kind,event_key FROM app_notification WHERE id=:id AND user_id=:user AND
                """ + RETAINED).param("id", id).param("user", user).query((row, index) -> new Event(row.getString(1), row.getString(2))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var kind = event.kind();
        var key = event.key();
        var target = switch (kind) {
            case "GUIDE" -> db.sql("""
                    SELECT g.content_id,d.attempt_id FROM guide_activity g
                    JOIN survey_attempt a ON a.user_id=g.user_id
                    JOIN diagnosis d ON d.attempt_id=a.id AND d.representative_type=g.representative_type
                    JOIN published_guide p ON p.content_key=g.content_id AND d.representative_type=ANY(p.types)
                    WHERE g.user_id=:user AND g.content_id=split_part(:key,':',1)
                    ORDER BY d.completed_at DESC,d.id DESC LIMIT 1
                    """).param("user", user).param("key", key)
                    .query((row, index) -> new Target(kind, row.getString(1), row.getObject(2, UUID.class), true))
                    .optional().orElse(new Target(kind, null, null, false));
            case "EXPORT" -> db.sql("""
                    SELECT id::text FROM app_data_export WHERE user_id=:user AND id::text=:key
                    AND status='COMPLETED' AND archive IS NOT NULL AND expires_at>now()
                    """).param("user", user).param("key", key).query(String.class).optional()
                    .map(resource -> new Target(kind, resource, null, true))
                    .orElse(new Target(kind, null, null, false));
            case "ECONOMY" -> new Target(kind, key, null, true);
            case "NOTICE" -> noticeTarget(key);
            default -> new Target(kind, null, null, true);
        };
        return privateResponse(target);
    }

    private Target noticeTarget(String key) {
        return db.sql("""
                SELECT r.title,r.summary,r.body, CASE
                    WHEN r.ends_at<=now() OR r.status='EXPIRED' THEN 'EXPIRED'
                    WHEN n.published_revision_id IS DISTINCT FROM r.id OR r.status<>'PUBLISHED'
                        OR r.starts_at IS NULL OR r.starts_at>now() THEN 'UNPUBLISHED'
                    END AS reason
                FROM service_notice_revision r JOIN service_notice n ON n.id=r.notice_id
                WHERE n.kind='NOTICE' AND r.id::text=:key
                """).param("key", key).query((row, index) -> {
            var reason = row.getString("reason");
            return reason == null
                    ? new Target("NOTICE", null, null, true,
                            new Notice(row.getString("title"), row.getString("summary"), row.getString("body")), null)
                    : new Target("NOTICE", null, null, false, null, reason);
        }).optional().orElse(new Target("NOTICE", null, null, false, null, "NOT_FOUND"));
    }

    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var user = lockActive(jwt);
        var changed = db.sql("""
                UPDATE app_notification SET read_at=coalesce(read_at,clock_timestamp())
                WHERE id=:id AND user_id=:user AND
                """ + RETAINED).param("id", id).param("user", user).update();
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private Preferences readPreferences(UUID id) {
        return db.sql("""
                SELECT enabled,periodic,incomplete,guide,economy FROM app_notification_preference WHERE user_id=:user
                """).param("user", id).query((row, index) -> new Preferences(row.getBoolean(1), row.getBoolean(2),
                row.getBoolean(3), row.getBoolean(4), row.getBoolean(5)))
                .optional().orElse(new Preferences(false, false, false, false, false));
    }

    private UUID lockActive(Jwt jwt) {
        var id = user(jwt);
        var active = db.sql("SELECT id FROM app_user WHERE id=:user AND status='ACTIVE' FOR UPDATE")
                .param("user", id).query(UUID.class).optional().isPresent();
        var session = UUID.fromString(jwt.getClaimAsString("sid"));
        if (!active || !db.sql("""
                SELECT EXISTS(SELECT 1 FROM app_session WHERE user_id=:user AND id=:session AND expires_at > now())
                """).param("user", id).param("session", session).query(Boolean.class).single()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return id;
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static <T> ResponseEntity<T> privateResponse(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
}
