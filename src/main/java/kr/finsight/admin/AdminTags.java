package kr.finsight.admin;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
class AdminTags {
    static final int USER_LIMIT = 20;
    record Tag(UUID id, String name, String status, long version) {}
    record Selection(List<UUID> tagIds) {}

    private final JdbcClient db;
    private final AdminMutationJournal journal;

    AdminTags(JdbcClient db, AdminMutationJournal journal) {
        this.db = db;
        this.journal = journal;
    }

    List<Tag> list() {
        return db.sql("SELECT id,name,status,version FROM tag WHERE status='ACTIVE' ORDER BY name,id")
                .query(Tag.class).list();
    }

    Tag create(AdminAuth.Identity actor, String input) {
        var name = displayName(input);
        var id = UUID.randomUUID();
        return journal.record(actor, "TAG_CREATED", "TAG", id.toString(), () -> db.sql("""
                INSERT INTO tag(id,name,name_normalized,status) VALUES (:id,:name,:normalized,'ACTIVE')
                ON CONFLICT(name_normalized) DO NOTHING RETURNING id,name,status,version
                """).param("id", id).param("name", name).param("normalized", normalized(name))
                .query(Tag.class).optional().orElseThrow(AdminTags::conflict), tag -> Map.of("version", tag.version()));
    }

    Tag rename(AdminAuth.Identity actor, UUID id, String input, long expectedVersion) {
        var name = displayName(input);
        try {
            return journal.record(actor, "TAG_RENAMED", "TAG", id.toString(), () -> {
                var current = db.sql("SELECT id,name,status,version FROM tag WHERE id=:id AND status='ACTIVE' FOR UPDATE")
                        .param("id", id).query(Tag.class).optional().orElseThrow(AdminTags::missing);
                if (current.version() != expectedVersion) throw conflict();
                return db.sql("""
                        UPDATE tag SET name=:name,name_normalized=:normalized,version=version+1 WHERE id=:id
                        RETURNING id,name,status,version
                        """).param("id", id).param("name", name).param("normalized", normalized(name))
                        .query(Tag.class).single();
            }, tag -> Map.of("previousVersion", expectedVersion, "version", tag.version()));
        } catch (DuplicateKeyException duplicate) {
            throw conflict();
        }
    }

    Selection selection(UUID user) {
        if (!db.sql("SELECT EXISTS(SELECT 1 FROM app_user WHERE id=:user)")
                .param("user", user).query(Boolean.class).single()) throw missing();
        return new Selection(selectedTags(user));
    }

    @Transactional
    Selection replace(AdminAuth.Identity actor, UUID user, List<UUID> requested, List<UUID> expected) {
        var target = asSet(requested);
        var baseline = asSet(expected);
        if (target.size() > USER_LIMIT) throw invalid();
        db.sql("SELECT id FROM app_user WHERE id=:user FOR UPDATE").param("user", user)
                .query(UUID.class).optional().orElseThrow(AdminTags::missing);
        var current = Set.copyOf(selectedTags(user));
        var result = new Selection(target.stream().sorted().toList());
        if (current.equals(target)) return result;
        if (!current.equals(baseline)) throw conflict();
        for (var id : result.tagIds()) {
            db.sql("SELECT id FROM tag WHERE id=:id AND status='ACTIVE' FOR SHARE")
                    .param("id", id).query(UUID.class).optional().orElseThrow(AdminTags::invalid);
        }
        for (var id : current) {
            if (!target.contains(id)) {
                db.sql("DELETE FROM user_tag WHERE user_id=:user AND tag_id=:tag")
                        .param("user", user).param("tag", id).update();
            }
        }
        for (var id : result.tagIds()) {
            if (!current.contains(id)) {
                db.sql("INSERT INTO user_tag(user_id,tag_id) VALUES (:user,:tag)")
                        .param("user", user).param("tag", id).update();
            }
        }
        journal.append(actor, "USER_TAGS_UPDATED", "APP_USER", user.toString(),
                Map.of("previousTagIds", current.stream().sorted().toList(), "tagIds", result.tagIds()));
        return result;
    }

    private List<UUID> selectedTags(UUID user) {
        return db.sql("""
                SELECT t.id FROM user_tag ut JOIN tag t ON t.id=ut.tag_id
                WHERE ut.user_id=:user AND t.status='ACTIVE' ORDER BY t.id
                """).param("user", user).query(UUID.class).list();
    }

    private static Set<UUID> asSet(List<UUID> values) {
        if (values == null || values.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        var distinct = new HashSet<>(values);
        if (distinct.size() != values.size()) throw invalid();
        return distinct;
    }

    private static String displayName(String value) {
        if (value == null) throw invalid();
        var name = Normalizer.normalize(value.replaceAll("(?U)\\s+", " ").strip(), Normalizer.Form.NFC);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 30
                || name.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return name;
    }

    private static String normalized(String name) { return name.toLowerCase(Locale.ROOT); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
