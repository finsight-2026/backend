package kr.finsight.content;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class InsightService {
    enum Topic { YFSI_MEAN, EXTERNAL_AREA_MEAN, RESOURCE_DOMAIN_MEAN, PERCEIVED_STRESS_MEAN }

    record Card(UUID id, int revision, String title, String summary, Topic topic, OffsetDateTime publishedAt, long helpfulCount) {}
    record Page(boolean diagnosed, List<Card> items, boolean hasMore) {}
    record Detail(Card card, String body, String explanation, LocalDate from, LocalDate to, int sampleSize,
                  String formulaVersion, JsonNode metric, boolean helpful) {}
    private record Diagnosis(String type, String formula) {}

    private static final int PAGE_SIZE = 20;
    private static final String SELECT = """
            SELECT i.id,i.revision_no,i.title,i.summary,i.metric_key,i.starts_at,i.body,i.insight_explanation,
                i.period_start,i.period_end,i.sample_n,i.formula_version,i.metric_value::text,
                reactions.total,reactions.mine
            FROM published_insight i
            CROSS JOIN LATERAL (
                SELECT count(*) AS total,coalesce(bool_or(h.user_id=:user),false) AS mine
                FROM insight_helpful h JOIN app_user u ON u.id=h.user_id
                WHERE h.content_id=i.id AND u.status='ACTIVE'
            ) reactions
            WHERE i.type=:type AND i.formula_version=:formula
            """;

    private final JdbcClient db;
    private final JsonMapper json;
    private final ContentEvents events;

    InsightService(JdbcClient db, JsonMapper json, ContentEvents events) {
        this.db = db;
        this.json = json;
        this.events = events;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Page list(UUID user, Topic topic, OffsetDateTime before, UUID beforeId) {
        if ((before == null) != (beforeId == null)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        var diagnosis = latest(user);
        if (diagnosis == null) return new Page(false, List.of(), false);
        var query = bind(SELECT + """
                AND (CAST(:topic AS text) IS NULL OR i.metric_key=:topic)
                """ + (before == null ? "" : " AND (i.starts_at,i.id)<(:before,:beforeId)")
                + " ORDER BY i.starts_at DESC,i.id DESC LIMIT :limit", user, diagnosis)
                .param("topic", topic == null ? null : topic.name(), Types.VARCHAR).param("limit", PAGE_SIZE + 1);
        if (before != null) query.param("before", before).param("beforeId", beforeId);
        var rows = query.query(this::card).list();
        return new Page(true, rows.stream().limit(PAGE_SIZE).toList(), rows.size() > PAGE_SIZE);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    Detail detail(UUID user, UUID id) {
        return detail(user, id, requireDiagnosis(user));
    }

    @Transactional
    Detail helpful(UUID user, UUID id, int revision, boolean value) {
        lockUser(user);
        var diagnosis = requireDiagnosis(user);
        var current = detail(user, id, diagnosis);
        if (current.card().revision() != revision) throw new ResponseStatusException(HttpStatus.CONFLICT);
        int changed;
        if (value) {
            changed = db.sql("""
                    INSERT INTO insight_helpful(content_id,user_id,revision_id)
                    SELECT :id,:user,r.id FROM service_notice_revision r WHERE r.notice_id=:id AND r.revision_no=:revision
                    ON CONFLICT (content_id,user_id) DO NOTHING
                    """).param("id", id).param("user", user).param("revision", revision).update();
        } else {
            changed = db.sql("DELETE FROM insight_helpful WHERE content_id=:id AND user_id=:user")
                    .param("id", id).param("user", user).update();
        }
        if (changed != 0) {
            db.sql("""
                    INSERT INTO app_event(event_name,user_id,content_kind,content_id,content_revision,content_type,completion_value,occurred_at,received_at)
                    VALUES ('HELPFUL_SET',:user,'INSIGHT',:content,:revision,:type,:value,statement_timestamp(),statement_timestamp())
                    """).param("user", user).param("content", id.toString()).param("revision", revision)
                    .param("type", diagnosis.type()).param("value", value).update();
        }
        return detail(user, id, diagnosis);
    }

    @Transactional
    long record(UUID user, UUID id, ContentEvents.Report report) {
        lockUser(user);
        var diagnosis = requireDiagnosis(user);
        var available = db.sql("""
                SELECT id FROM published_insight WHERE id=:id AND revision_no=:revision AND type=:type AND formula_version=:formula
                """).param("id", id).param("revision", report.revision()).param("type", diagnosis.type())
                .param("formula", diagnosis.formula()).query(UUID.class).optional();
        if (available.isEmpty()) throw missing();
        return events.record(user, ContentEvents.Kind.INSIGHT, id.toString(), report.revision(), diagnosis.type(),
                report.action() == ContentEvents.Action.IMPRESSION ? ContentEvents.Placement.INSIGHT_LIST : null, report);
    }

    private Detail detail(UUID user, UUID id, Diagnosis diagnosis) {
        return bind(SELECT + " AND i.id=:id", user, diagnosis).param("id", id).query((row, n) -> new Detail(
                card(row, n), row.getString(7), row.getString(8), row.getObject(9, LocalDate.class),
                row.getObject(10, LocalDate.class), row.getInt(11), row.getString(12),
                json.readTree(row.getString(13)), row.getBoolean(15))).optional().orElseThrow(InsightService::missing);
    }

    private Card card(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new Card(row.getObject(1, UUID.class), row.getInt(2), row.getString(3), row.getString(4),
                Topic.valueOf(row.getString(5)), row.getObject(6, OffsetDateTime.class), row.getLong(14));
    }

    private JdbcClient.StatementSpec bind(String sql, UUID user, Diagnosis diagnosis) {
        return db.sql(sql).param("user", user).param("type", diagnosis.type()).param("formula", diagnosis.formula());
    }

    private Diagnosis latest(UUID user) {
        return db.sql("""
                SELECT d.representative_type,a.formula_version FROM diagnosis d JOIN survey_attempt a ON a.id=d.attempt_id
                JOIN app_user u ON u.id=a.user_id
                WHERE a.user_id=:user AND u.status='ACTIVE' ORDER BY d.completed_at DESC,d.id DESC LIMIT 1
                """).param("user", user).query((row, n) -> new Diagnosis(row.getString(1), row.getString(2)))
                .optional().orElse(null);
    }

    private Diagnosis requireDiagnosis(UUID user) {
        var diagnosis = latest(user);
        if (diagnosis == null) throw missing();
        return diagnosis;
    }

    private void lockUser(UUID user) {
        db.sql("SELECT id FROM app_user WHERE id=:user AND status='ACTIVE' FOR UPDATE").param("user", user)
                .query(UUID.class).optional().orElseThrow(InsightService::missing);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
