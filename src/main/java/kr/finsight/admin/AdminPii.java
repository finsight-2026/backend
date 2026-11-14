package kr.finsight.admin;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class AdminPii {
    enum Scope { PROFILE, ANSWERS, EXPORT }
    record Grant(UUID grantId, OffsetDateTime expiresAt) {}
    record ProfileData(String email, JsonNode attributes) {}
    record Answer(int questionNo, int response) {}
    record Data(ProfileData profile, UUID diagnosisId, List<Answer> answers, OffsetDateTime expiresAt) {}
    private record Permission(List<String> scopes, OffsetDateTime expiresAt) {}

    private final JdbcClient db;
    private final AdminMutationJournal journal;
    private final JsonMapper json;
    private final BytesEncryptor encryption;
    private final BytesEncryptor exportEncryption;

    AdminPii(JdbcClient db, AdminMutationJournal journal, JsonMapper json,
             @Value("${finsight.admin.pii-key:}") String key) {
        this.db = db;
        this.journal = journal;
        this.json = json;
        if (!key.isBlank() && !key.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("개인정보 열람 사유 암호화 키는 64자리 16진수여야 합니다.");
        }
        encryption = key.isBlank() ? null : Encryptors.stronger(key, "46696e73696768745069694772616e747631");
        exportEncryption = key.isBlank() ? null : Encryptors.stronger(key, "46696e53696768745069694578706f727431");
    }

    @Transactional
    Grant issue(AdminAuth.Identity actor, String token, UUID user, String input, List<Scope> scope) {
        var reason = input == null ? "" : input.strip();
        var length = reason.codePointCount(0, reason.length());
        if (length < 10 || length > 500 || scope == null || scope.isEmpty() || scope.size() > Scope.values().length
                || scope.stream().anyMatch(java.util.Objects::isNull) || scope.stream().distinct().count() != scope.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var hash = AdminAuth.hash(token);
        authorize(actor.admin().id(), hash, true);
        lockUser(user);
        if (encryption == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var id = UUID.randomUUID();
        var expires = db.sql("""
                INSERT INTO pii_grant(id,admin_id,session_hash,user_id,scopes,reason_encrypted,expires_at)
                VALUES (:id,:admin,:hash,:user,ARRAY(SELECT jsonb_array_elements_text(CAST(:scope AS jsonb))),
                        :reason,clock_timestamp()+interval '10 minutes') RETURNING expires_at
                """).param("id", id).param("admin", actor.admin().id()).param("hash", hash).param("user", user)
                .param("scope", json.writeValueAsString(scope))
                .param("reason", encryption.encrypt(reason.getBytes(StandardCharsets.UTF_8)))
                .query(OffsetDateTime.class).single();
        journal.append(actor, "PII_GRANT_ISSUED", "APP_USER", user.toString(),
                Map.of("grantId", id, "scopes", scope));
        return new Grant(id, expires);
    }

    @Transactional
    Data read(AdminAuth.Identity actor, String token, UUID user, UUID id, UUID diagnosis) {
        var hash = AdminAuth.hash(token);
        authorize(actor.admin().id(), hash, false);
        lockUser(user);
        var grant = permission(actor.admin().id(), hash, user, id);
        boolean profileAllowed = grant.scopes().contains("PROFILE");
        boolean answersAllowed = grant.scopes().contains("ANSWERS");
        if ((!profileAllowed && !answersAllowed) || (diagnosis != null && !answersAllowed)) throw forbidden();
        if (!profileAllowed && diagnosis == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        ProfileData profile = profileAllowed ? profile(user) : null;
        List<Answer> answers = List.of();
        if (diagnosis != null) {
            verifyDiagnosis(user, diagnosis);
            answers = answers(diagnosis);
        }
        var scopesRead = new java.util.ArrayList<Scope>();
        if (profileAllowed) scopesRead.add(Scope.PROFILE);
        if (diagnosis != null) scopesRead.add(Scope.ANSWERS);
        journal.append(actor, "PII_VIEWED", "APP_USER", user.toString(), Map.of("grantId", id, "scopes", scopesRead));
        return new Data(profile, diagnosis, answers, grant.expiresAt());
    }

    OffsetDateTime authorizeExport(UUID admin, String hash, UUID user, UUID id, Scope scope, UUID diagnosis, boolean recent) {
        authorize(admin, hash, recent);
        db.sql("SELECT id FROM app_user WHERE id=:id AND status='ACTIVE' FOR SHARE").param("id", user)
                .query(UUID.class).optional().orElseThrow(AdminPii::forbidden);
        var grant = permission(admin, hash, user, id);
        if (!grant.scopes().contains("EXPORT") || !grant.scopes().contains(scope.name())) throw forbidden();
        if (diagnosis != null) verifyDiagnosis(user, diagnosis);
        return grant.expiresAt();
    }

    private Permission permission(UUID admin, String hash, UUID user, UUID id) {
        return db.sql("""
                SELECT scopes,expires_at FROM pii_grant WHERE id=:id AND admin_id=:admin AND session_hash=:hash
                AND user_id=:user AND expires_at>clock_timestamp() AND revoked_at IS NULL FOR SHARE
                """).param("id", id).param("admin", admin).param("hash", hash).param("user", user)
                .query((row, n) -> new Permission(List.of((String[]) row.getArray(1).getArray()),
                        row.getObject(2, OffsetDateTime.class))).optional().orElseThrow(AdminPii::forbidden);
    }

    ProfileData profile(UUID user) {
        return db.sql("""
                SELECT u.email,coalesce(p.attributes,'{}'::jsonb) FROM app_user u
                LEFT JOIN app_user_profile p ON p.user_id=u.id WHERE u.id=:id
                """).param("id", user).query((row, n) -> new ProfileData(row.getString(1),
                json.readTree(row.getString(2)))).single();
    }

    private void verifyDiagnosis(UUID user, UUID diagnosis) {
        if (!db.sql("""
                SELECT EXISTS(SELECT 1 FROM diagnosis d JOIN survey_attempt a ON a.id=d.attempt_id
                    WHERE d.id=:diagnosis AND a.user_id=:user)
                """).param("diagnosis", diagnosis).param("user", user).query(Boolean.class).single()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    List<Answer> answers(UUID diagnosis) {
        return db.sql("""
                SELECT question_no,response FROM diagnosis_answer WHERE diagnosis_id=:id ORDER BY question_no
                """).param("id", diagnosis).query((row, n) -> new Answer(row.getInt(1), row.getInt(2))).list();
    }

    byte[] protectExport(byte[] data) {
        if (exportEncryption == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return exportEncryption.encrypt(data);
    }

    byte[] openExport(byte[] data) {
        if (exportEncryption == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return exportEncryption.decrypt(data);
    }

    @Transactional
    void revoke(AdminAuth.Identity actor, String token, UUID user, UUID id) {
        int changed = db.sql("""
                UPDATE pii_grant SET revoked_at=clock_timestamp()
                WHERE id=:id AND admin_id=:admin AND session_hash=:hash AND user_id=:user AND revoked_at IS NULL
                """).param("id", id).param("admin", actor.admin().id()).param("hash", AdminAuth.hash(token))
                .param("user", user).update();
        if (changed != 0) journal.append(actor, "PII_GRANT_REVOKED", "APP_USER", user.toString(), Map.of("grantId", id));
    }

    void authorize(UUID admin, String hash, boolean recent) {
        var version = db.sql("""
                SELECT a.session_version FROM admin_account a JOIN admin_role r ON r.admin_id=a.id AND r.role='PRIVACY'
                WHERE a.id=:admin AND a.status='ACTIVE' FOR SHARE OF a,r
                """).param("admin", admin).query(Long.class).optional().orElseThrow(AdminPii::forbidden);
        var verified = db.sql("""
                SELECT reauth_at>clock_timestamp()-interval '5 minutes' FROM admin_session
                WHERE id_hash=:hash AND admin_id=:admin AND session_version=:version
                AND expires_at>clock_timestamp() AND last_interaction_at>clock_timestamp()-interval '30 minutes'
                FOR SHARE
                """).param("hash", hash).param("admin", admin).param("version", version)
                .query(Boolean.class).optional().orElseThrow(AdminPii::forbidden);
        if (recent && !verified) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED);
    }

    private void lockUser(UUID user) {
        db.sql("SELECT id FROM app_user WHERE id=:id AND status<>'DATA_DELETION_PENDING' FOR SHARE").param("id", user)
                .query(UUID.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN); }
}
