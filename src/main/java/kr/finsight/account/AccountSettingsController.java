package kr.finsight.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@Profile("local")
@RequestMapping("/api/v1/account")
@CrossOrigin(origins = "${finsight.signup.web-origin:http://localhost:18081}")
class AccountSettingsController {
    record Identity(String email, boolean emailVerified, OffsetDateTime lastLoginAt, JsonNode profile,
                    boolean passwordEnabled, List<SocialAccounts.Provider> socialProviders) {}
    record Consent(String kind, String version, boolean granted, OffsetDateTime recordedAt, String method) {}
    record Research(Boolean granted, String version) {}
    private record ResearchDecision(String version, boolean granted) {}

    private final JdbcClient db;
    private final JsonMapper json;
    private final PolicyDocuments policies;
    private final SocialAccounts socialAccounts;

    AccountSettingsController(JdbcClient db, JsonMapper json, PolicyDocuments policies, SocialAccounts socialAccounts) {
        this.db = db;
        this.json = json;
        this.policies = policies;
        this.socialAccounts = socialAccounts;
    }

    @GetMapping("/settings")
    ResponseEntity<Identity> settings(@AuthenticationPrincipal Jwt jwt) {
        var user = user(jwt);
        var providers = socialAccounts.providers(user);
        var identity = db.sql("""
                SELECT u.email,u.password_hash IS NOT NULL,u.last_login_at,coalesce(p.attributes,'{}'::jsonb)
                FROM app_user u LEFT JOIN app_user_profile p ON p.user_id=u.id
                WHERE u.id=:user AND u.status IN ('ACTIVE','DATA_DELETION_PENDING')
                """).param("user", user).query((row, index) -> new Identity(row.getString(1),
                row.getBoolean(2) || !providers.isEmpty(), row.getObject(3, OffsetDateTime.class),
                json.readTree(row.getString(4)), row.getBoolean(2), providers)).optional()
                .orElseThrow(AccountSettingsController::unauthorized);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(identity);
    }

    @PutMapping("/settings/profile")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void profile(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, String> input) {
        Map<String, String> validated;
        try {
            validated = ProfileInput.validate(input);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var id = lockActive(jwt);
        db.sql("""
                INSERT INTO app_user_profile (user_id, attributes) VALUES (:user, CAST(:profile AS jsonb))
                ON CONFLICT (user_id) DO UPDATE SET attributes=excluded.attributes
                """).param("user", id).param("profile", json.writeValueAsString(validated)).update();
    }

    @GetMapping("/settings/consents")
    ResponseEntity<List<Consent>> consents(@AuthenticationPrincipal Jwt jwt) {
        var history = db.sql("""
                SELECT c.document_kind,c.document_version,c.granted,c.recorded_at,c.method
                FROM app_user_consent c JOIN app_user u ON u.id=c.user_id
                WHERE c.user_id=:user AND u.status IN ('ACTIVE','DATA_DELETION_PENDING') ORDER BY c.recorded_at DESC,c.id DESC
                """).param("user", user(jwt)).query((row, index) -> new Consent(row.getString(1), row.getString(2),
                row.getBoolean(3), row.getObject(4, OffsetDateTime.class), row.getString(5))).list();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(history);
    }

    @PutMapping("/settings/consents/research")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void research(@AuthenticationPrincipal Jwt jwt, @RequestBody Research input) {
        if (input.granted() == null || (input.granted() && input.version() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var id = lockActive(jwt);
        var current = db.sql("""
                SELECT document_version AS version, granted FROM app_user_consent
                WHERE user_id=:user AND document_kind='RESEARCH' ORDER BY recorded_at DESC,id DESC LIMIT 1
                """).param("user", id).query(ResearchDecision.class).optional();
        String version;
        if (input.granted()) {
            version = policies.activeVersion().filter(input.version()::equals)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "현재 연구 안내를 확인해주세요."));
            if (current.filter(decision -> decision.granted() && decision.version().equals(version)).isPresent()) return;
        } else {
            var granted = current.filter(ResearchDecision::granted);
            if (granted.isEmpty()) return;
            version = granted.get().version();
        }
        db.sql("""
                INSERT INTO app_user_consent(user_id,document_kind,document_version,granted,recorded_at,method)
                VALUES (:user,'RESEARCH',:version,:granted,clock_timestamp(),'APP_SETTINGS')
                """).param("user", id).param("version", version).param("granted", input.granted()).update();
    }

    private UUID lockActive(Jwt jwt) {
        var id = db.sql("SELECT id FROM app_user WHERE id=:user AND status IN ('ACTIVE','DATA_DELETION_PENDING') FOR UPDATE")
                .param("user", user(jwt)).query(UUID.class).optional().orElseThrow(AccountSettingsController::unauthorized);
        var session = UUID.fromString(jwt.getClaimAsString("sid"));
        var active = db.sql("""
                SELECT EXISTS(SELECT 1 FROM app_session WHERE user_id=:user AND id=:session AND expires_at > now())
                """).param("user", id).param("session", session).query(Boolean.class).single();
        if (!active) throw unauthorized();
        return id;
    }

    private static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
