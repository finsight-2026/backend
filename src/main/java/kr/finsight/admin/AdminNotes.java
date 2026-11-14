package kr.finsight.admin;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminNotes {
    record Note(UUID id, UUID authorId, String text, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    record Create(String text) {}
    record Edit(String text, Long expectedVersion) {}
    record Delete(Long expectedVersion, String reason) {}
    private record Stored(UUID author, byte[] encrypted, long version) {}

    private final JdbcClient db;
    private final AdminMutationJournal journal;
    private final BytesEncryptor encryption;
    private final SecretKeySpec requestKey;

    AdminNotes(JdbcClient db, AdminMutationJournal journal, @Value("${finsight.admin.note-key:}") String key) {
        this.db = db;
        this.journal = journal;
        if (!key.isBlank() && !key.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("운영 메모 암호화 키는 64자리 16진수여야 합니다.");
        }
        encryption = key.isBlank() ? null : Encryptors.stronger(key, "46696e73696768744e6f7465737631");
        requestKey = key.isBlank() ? null : new SecretKeySpec(HexFormat.of().parseHex(key), "HmacSHA256");
    }

    List<Note> list(UUID user) {
        if (!db.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE id=:id)")
                .param("id", user).query(Boolean.class).single()) throw missing();
        return db.sql("""
                SELECT id,author_id,text_encrypted,version,created_at,updated_at FROM operator_note
                WHERE user_id=:user AND deleted_at IS NULL
                  AND EXISTS(SELECT 1 FROM app_user u WHERE u.id=:user AND u.status<>'DATA_DELETION_PENDING')
                ORDER BY created_at DESC,id DESC
                """).param("user", user).query(this::read).list();
    }

    Note create(AdminAuth.Identity actor, UUID user, Create input, String key) {
        var text = validate(input.text());
        var id = journal.once(actor, "POST /users/" + user + "/notes", key, fingerprint(text), UUID.class, () -> {
            lockUser(user);
            var note = UUID.randomUUID();
            db.sql("""
                    INSERT INTO operator_note(id,user_id,author_id,text_encrypted) VALUES (:id,:user,:author,:text)
                    """).param("id", note).param("user", user).param("author", actor.admin().id())
                    .param("text", encrypt(text)).update();
            return note;
        }, note -> journal.append(actor, "USER_NOTE_CREATED", "OPERATOR_NOTE", note.toString(), Map.of("version", 0)));
        return find(user, id);
    }

    Note edit(AdminAuth.Identity actor, UUID user, UUID id, Edit input) {
        var text = validate(input.text());
        return journal.record(actor, "USER_NOTE_UPDATED", "OPERATOR_NOTE", id.toString(), () -> {
            var old = lockNote(actor, user, id, input.expectedVersion());
            archive(actor, id, old);
            db.sql("""
                    UPDATE operator_note SET text_encrypted=:text,version=version+1,updated_at=clock_timestamp() WHERE id=:id
                    """).param("text", encrypt(text)).param("id", id).update();
            return find(user, id);
        }, note -> Map.of("previousVersion", note.version()-1, "version", note.version()));
    }

    void delete(AdminAuth.Identity actor, UUID user, UUID id, Delete input) {
        var reason = validate(input.reason());
        journal.record(actor, "USER_NOTE_DELETED", "OPERATOR_NOTE", id.toString(), () -> {
            var old = lockNote(actor, user, id, input.expectedVersion());
            archive(actor, id, old);
            db.sql("""
                    UPDATE operator_note SET deleted_at=clock_timestamp(),updated_at=clock_timestamp(),version=version+1
                    WHERE id=:id
                    """).param("id", id).update();
            return old.version();
        }, version -> Map.of("previousVersion", version, "reasonLength", reason.codePointCount(0, reason.length())));
    }

    private Stored lockNote(AdminAuth.Identity actor, UUID user, UUID id, Long expected) {
        lockUser(user);
        var note = db.sql("""
                SELECT author_id,text_encrypted,version FROM operator_note WHERE user_id=:user AND id=:id
                AND deleted_at IS NULL FOR UPDATE
                """).param("user", user).param("id", id).query((row, n) ->
                new Stored(row.getObject(1, UUID.class), row.getBytes(2), row.getLong(3))).optional()
                .orElseThrow(AdminNotes::missing);
        if (!note.author().equals(actor.admin().id()) && !actor.permissions().contains("SUPER_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (expected == null || expected != note.version()) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return note;
    }

    private void archive(AdminAuth.Identity actor, UUID id, Stored note) {
        db.sql("INSERT INTO note_history(note_id,version,text_encrypted,actor_id) VALUES (:id,:version,:text,:actor)")
                .param("id", id).param("version", note.version()).param("text", note.encrypted())
                .param("actor", actor.admin().id()).update();
    }

    private void lockUser(UUID user) {
        db.sql("SELECT id FROM app_user WHERE id=:id AND status<>'DATA_DELETION_PENDING' FOR UPDATE").param("id", user).query(UUID.class)
                .optional().orElseThrow(AdminNotes::missing);
    }

    private Note find(UUID user, UUID id) {
        return db.sql("""
                SELECT id,author_id,text_encrypted,version,created_at,updated_at FROM operator_note
                WHERE user_id=:user AND id=:id AND deleted_at IS NULL
                  AND EXISTS(SELECT 1 FROM app_user u WHERE u.id=:user AND u.status<>'DATA_DELETION_PENDING')
                """).param("user", user).param("id", id).query(this::read).optional().orElseThrow(AdminNotes::missing);
    }

    private Note read(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        var text = new String(cipher().decrypt(row.getBytes(3)), StandardCharsets.UTF_8);
        return new Note(row.getObject(1, UUID.class), row.getObject(2, UUID.class), text, row.getLong(4),
                row.getObject(5, OffsetDateTime.class), row.getObject(6, OffsetDateTime.class));
    }

    private byte[] encrypt(String text) {
        return cipher().encrypt(text.getBytes(StandardCharsets.UTF_8));
    }

    private String fingerprint(String text) {
        cipher();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(requestKey);
            return HexFormat.of().formatHex(mac.doFinal(("note-create:" + text).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("운영 메모 요청 식별자를 생성하지 못했습니다.", error);
        }
    }

    private BytesEncryptor cipher() {
        if (encryption == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "운영 메모 암호화 키가 필요합니다.");
        return encryption;
    }

    private static String validate(String input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        var text = input.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > 500 || text.indexOf('<') >= 0 || text.indexOf('>') >= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return text;
    }

    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
