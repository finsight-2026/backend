package kr.finsight.admin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class AdminBulkTags {
    enum Action { ADD_TAGS, REMOVE_TAGS }
    record Preview(UUID previewId, long total, long excluded, OffsetDateTime expiresAt) {}
    record Job(UUID id, String status, long total, long excluded, long changed) {}
    private record Work(UUID id, UUID previewId, UUID actorId, String action, String tagIds, long total) {}

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AdminBulkTags.class);
    private static final String JOB_FIELDS = "id,status,total,excluded,changed";
    private static final String TAG_IDS = "SELECT value::uuid FROM jsonb_array_elements_text(CAST(:tags AS jsonb))";
    private static final String ELIGIBLE = """
            u.status='ACTIVE' AND (:action='REMOVE_TAGS' OR (
                SELECT count(*) FROM (
                    SELECT ut.tag_id FROM user_tag ut JOIN tag existing ON existing.id=ut.tag_id
                    WHERE ut.user_id=u.id AND existing.status='ACTIVE'
                    UNION
            """ + TAG_IDS + """
                ) combined
            )<=:limit)
            """;
    private final JdbcClient db;
    private final AdminUsers users;
    private final AdminMutationJournal journal;
    private final JsonMapper json;
    private final TransactionTemplate transactions;

    AdminBulkTags(JdbcClient db, AdminUsers users, AdminMutationJournal journal, JsonMapper json, TransactionTemplate transactions) {
        this.db = db;
        this.users = users;
        this.journal = journal;
        this.json = json;
        this.transactions = transactions;
    }

    @Transactional
    Preview preview(AdminAuth.Identity actor, AdminUsers.Filters filter, List<UUID> explicit, Action action, List<UUID> tags) {
        if ((filter == null) == (explicit == null) || action == null || tags == null || tags.isEmpty()
                || tags.size() > AdminTags.USER_LIMIT || invalidIds(tags)
                || (explicit != null && (explicit.isEmpty() || explicit.size() > 100 || invalidIds(explicit)))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var tagIds = json.writeValueAsString(tags);
        if (activeTags(tagIds) != tags.size()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        var id = UUID.randomUUID();
        var expires = db.sql("""
                INSERT INTO bulk_tag_preview(id,actor_id,action,tag_ids)
                VALUES (:id,:actor,:action,CAST(:tags AS jsonb)) RETURNING expires_at
                """).param("id", id).param("actor", actor.admin().id()).param("action", action.name())
                .param("tags", tagIds).query(OffsetDateTime.class).single();
        long total = filter == null ? explicit.size() : users.snapshotMatches(id, filter);
        if (explicit != null) {
            db.sql("""
                    INSERT INTO bulk_tag_target(preview_id,user_id) SELECT :preview,u.id FROM app_user u
                    WHERE u.id IN (SELECT value::uuid FROM jsonb_array_elements_text(CAST(:users AS jsonb)))
                    """).param("preview", id).param("users", json.writeValueAsString(explicit)).update();
        }
        db.sql("""
                DELETE FROM bulk_tag_target t USING app_user u
                WHERE t.preview_id=:preview AND t.user_id=u.id AND NOT (
                """ + ELIGIBLE + ")").param("preview", id).param("tags", tagIds)
                .param("action", action.name()).param("limit", AdminTags.USER_LIMIT).update();
        long included = db.sql("SELECT count(*) FROM bulk_tag_target WHERE preview_id=:preview")
                .param("preview", id).query(Long.class).single();
        db.sql("UPDATE bulk_tag_preview SET total=:total,excluded=:excluded WHERE id=:id")
                .param("id", id).param("total", total).param("excluded", total-included).update();
        return new Preview(id, total, total-included, expires);
    }

    @Transactional
    Job confirm(AdminAuth.Identity actor, UUID preview) {
        var owner = actor.admin().id();
        var previous = db.sql("SELECT " + JOB_FIELDS + " FROM bulk_tag_job WHERE preview_id=:preview AND actor_id=:actor")
                .param("preview", preview).param("actor", owner).query(Job.class).optional();
        if (previous.isPresent()) return previous.get();
        db.sql("SELECT id FROM bulk_tag_preview WHERE id=:id AND actor_id=:actor FOR UPDATE")
                .param("id", preview).param("actor", owner).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        previous = db.sql("SELECT " + JOB_FIELDS + " FROM bulk_tag_job WHERE preview_id=:preview AND actor_id=:actor")
                .param("preview", preview).param("actor", owner).query(Job.class).optional();
        if (previous.isPresent()) return previous.get();
        var job = db.sql("""
                INSERT INTO bulk_tag_job(id,preview_id,actor_id,status,total,excluded)
                SELECT :job,id,actor_id,'QUEUED',total,excluded FROM bulk_tag_preview
                WHERE id=:preview AND expires_at>clock_timestamp() AND total>excluded
                RETURNING
                """ + JOB_FIELDS).param("job", UUID.randomUUID()).param("preview", preview)
                .query(Job.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        journal.append(actor, "USER_BULK_TAG_QUEUED", "BULK_TAG_JOB", job.id().toString(),
                Map.of("total", job.total(), "excluded", job.excluded()));
        return job;
    }

    Job job(UUID actor, UUID id) {
        return db.sql("SELECT " + JOB_FIELDS + " FROM bulk_tag_job WHERE id=:id AND actor_id=:actor")
                .param("id", id).param("actor", actor).query(Job.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    void process() {
        db.sql("""
                SELECT id FROM bulk_tag_job WHERE status='QUEUED' ORDER BY created_at,id LIMIT 10
                """).query(UUID.class).list().forEach(id -> {
            try {
                transactions.executeWithoutResult(status -> run(id));
            } catch (RuntimeException failure) {
                LOG.error("일괄 태그 작업 실패 {}", id, failure);
                db.sql("""
                        UPDATE bulk_tag_job SET status='FAILED',completed_at=clock_timestamp(),error_code='LOCAL_PROCESSING_ERROR'
                        WHERE id=:id AND status='QUEUED'
                        """).param("id", id).update();
            }
        });
    }

    void run(UUID id) {
        var work = db.sql("""
                SELECT j.id,j.preview_id,j.actor_id,p.action,p.tag_ids::text,j.total
                FROM bulk_tag_job j JOIN bulk_tag_preview p ON p.id=j.preview_id
                WHERE j.id=:id AND j.status='QUEUED' FOR UPDATE OF j SKIP LOCKED
                """).param("id", id).query((row, n) -> new Work(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), row.getObject(3, UUID.class), row.getString(4),
                row.getString(5), row.getLong(6))).optional();
        if (work.isEmpty()) return;
        var task = work.get();
        db.sql("UPDATE bulk_tag_job SET started_at=clock_timestamp() WHERE id=:id")
                .param("id", id).update();
        var operator = db.sql("""
                SELECT a.id FROM admin_account a JOIN admin_role r ON r.admin_id=a.id
                WHERE a.id=:actor AND a.status='ACTIVE' AND r.role='OPERATOR' FOR SHARE OF a,r
                """).param("actor", task.actorId()).query(UUID.class).optional();
        if (operator.isEmpty()) {
            finish(task.id(), "FAILED", task.total(), 0, "OPERATOR_INACTIVE");
            return;
        }

        db.sql("""
                SELECT u.id FROM bulk_tag_target t JOIN app_user u ON u.id=t.user_id
                WHERE t.preview_id=:preview ORDER BY u.id FOR UPDATE OF u
                """).param("preview", task.previewId()).query(UUID.class).list();
        long selectedTags = json.readTree(task.tagIds()).size();
        if (activeTags(task.tagIds()) != selectedTags) {
            finish(task.id(), "FAILED", task.total(), 0, "TAG_UNAVAILABLE");
            return;
        }
        var candidates = """
                SELECT u.id FROM bulk_tag_target t JOIN app_user u ON u.id=t.user_id
                WHERE t.preview_id=:preview AND
                """ + ELIGIBLE;
        long included = eligibility("SELECT count(*) FROM (" + candidates + ") eligible", task)
                .query(Long.class).single();
        var change = task.action().equals("ADD_TAGS") ? """
                INSERT INTO user_tag(user_id,tag_id)
                SELECT e.id,t.id FROM eligible e CROSS JOIN target_tags t
                ON CONFLICT DO NOTHING RETURNING user_id
                """ : """
                DELETE FROM user_tag ut USING eligible e,target_tags t
                WHERE ut.user_id=e.id AND ut.tag_id=t.id RETURNING ut.user_id
                """;
        long changed = eligibility("WITH eligible AS (" + candidates + "), target_tags(id) AS (" + TAG_IDS
                + "), changed AS (" + change + ") SELECT count(DISTINCT user_id) FROM changed", task)
                .query(Long.class).single();
        finish(task.id(), "COMPLETED", task.total()-included, changed, null);
        journal.append(new AdminAuth.Identity(new AdminAuth.Admin(task.actorId(), ""), List.of("OPERATOR")),
                "USER_BULK_TAG_COMPLETED", "BULK_TAG_JOB", task.id().toString(),
                Map.of("action", task.action(), "excluded", task.total()-included, "changed", changed));
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Transactional
    void expire() {
        db.sql("""
                UPDATE bulk_tag_job j SET status='FAILED',completed_at=clock_timestamp(),error_code='PREVIEW_EXPIRED'
                FROM bulk_tag_preview p WHERE p.id=j.preview_id AND j.status='QUEUED'
                AND p.created_at<=clock_timestamp()-interval '24 hours'
                """).update();
        db.sql("DELETE FROM bulk_tag_preview WHERE created_at<=clock_timestamp()-interval '24 hours'").update();
    }

    private void finish(UUID id, String status, long excluded, long changed, String error) {
        db.sql("""
                UPDATE bulk_tag_job SET status=:status,excluded=:excluded,changed=:changed,
                completed_at=clock_timestamp(),error_code=:error WHERE id=:id
                """).param("id", id).param("status", status).param("excluded", excluded).param("changed", changed)
                .param("error", error, java.sql.Types.VARCHAR).update();
    }

    private JdbcClient.StatementSpec eligibility(String sql, Work task) {
        return db.sql(sql).param("preview", task.previewId()).param("action", task.action())
                .param("tags", task.tagIds()).param("limit", AdminTags.USER_LIMIT);
    }

    private int activeTags(String ids) {
        return db.sql("SELECT id FROM tag WHERE status='ACTIVE' AND id IN (" + TAG_IDS + ") ORDER BY id FOR SHARE")
                .param("tags", ids).query(UUID.class).list().size();
    }

    private static boolean invalidIds(List<UUID> ids) {
        return ids.stream().anyMatch(java.util.Objects::isNull) || ids.stream().distinct().count() != ids.size();
    }
}
