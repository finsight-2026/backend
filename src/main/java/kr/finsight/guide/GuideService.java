package kr.finsight.guide;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.finsight.content.ContentEvents;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local")
public class GuideService {
    public record Content(String id, String type, int version, int order, String title, String heading,
                   String action, List<String> steps, String body, String category) {}
    record Diagnosis(UUID attemptId, String type, int score) {}
    record Catalog(Diagnosis diagnosis, List<Content> guides) {}
    record Detail(Content guide, boolean completed) {}
    record WidgetAction(String state, UUID attemptId, String id, Integer revision, String title) {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcClient db;
    private final ContentEvents events;

    GuideService(JdbcClient db, ContentEvents events) {
        this.db = db;
        this.events = events;
    }

    private static final String CONTENT = """
            SELECT content_key,revision_no,sort_order,title,summary,today_action,next_action,body,guide_category
            FROM published_guide WHERE :type=ANY(types)
            """;

    private Content item(String type, String id) {
        return db.sql(CONTENT + " AND content_key=:id").param("type", type).param("id", id)
                .query((row, index) -> content(row, type)).optional().orElseThrow(GuideService::missing);
    }

    private static Content content(java.sql.ResultSet row, String type) throws java.sql.SQLException {
        return new Content(row.getString(1), type, row.getInt(2), row.getInt(3), row.getString(4),
                row.getString(5), row.getString(6), row.getString(7).lines().map(String::strip)
                        .filter(line -> !line.isEmpty()).toList(), row.getString(8), row.getString(9));
    }

    @Transactional
    long impression(UUID user, UUID attempt, String id, ContentEvents.Report input) {
        if (input.action() != ContentEvents.Action.IMPRESSION) throw missing();
        db.sql("SELECT id FROM app_user WHERE id=:id AND status='ACTIVE' FOR UPDATE")
                .param("id", user).query(UUID.class).optional().orElseThrow(GuideService::missing);
        var diagnosis = diagnosis(user, attempt);
        var guide = item(diagnosis.type(), id);
        if (guide.version() != input.revision()) throw missing();
        return events.record(user, ContentEvents.Kind.GUIDE, guide.id(), guide.version(), guide.type(),
                ContentEvents.Placement.GUIDE_LIST, input);
    }

    Catalog list(UUID user, UUID attempt) {
        var diagnosis = diagnosis(user, attempt);
        var items = db.sql(CONTENT + " ORDER BY sort_order,content_key").param("type", diagnosis.type())
                .query((row, index) -> content(row, diagnosis.type())).list();
        return new Catalog(diagnosis, items);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    WidgetAction widgetAction(UUID user) {
        Diagnosis latest;
        try {
            latest = diagnosis(user, null);
        } catch (ResponseStatusException error) {
            if (error.getStatusCode() != HttpStatus.NOT_FOUND) throw error;
            return new WidgetAction("NO_DIAGNOSIS", null, null, null, null);
        }
        var items = db.sql("""
                SELECT g.content_key,g.revision_no,g.today_action,
                       EXISTS (SELECT 1 FROM guide_activity a
                               WHERE a.user_id=:user AND a.content_id=g.content_key
                                 AND a.month_start=:month AND a.completed_at IS NOT NULL) AS completed
                FROM published_guide g WHERE :type=ANY(g.types) AND btrim(g.today_action)<>''
                ORDER BY g.sort_order,g.content_key
                """).param("user", user).param("month", LocalDate.now(KST).withDayOfMonth(1))
                .param("type", latest.type()).query((row, index) -> new WidgetAction(
                        row.getBoolean("completed") ? "COMPLETE" : "AVAILABLE", latest.attemptId(),
                        row.getString(1), row.getInt(2), row.getString(3))).list();
        return items.stream().filter(item -> item.state().equals("AVAILABLE")).findFirst()
                .orElseGet(() -> new WidgetAction(items.isEmpty() ? "UNAVAILABLE" : "COMPLETE", latest.attemptId(), null, null, null));
    }

    @Transactional
    Detail view(UUID user, UUID attempt, String id) {
        var month = LocalDate.now(KST).withDayOfMonth(1);
        var content = recordView(user, attempt, id, month);
        var completed = db.sql("""
                SELECT completed_at IS NOT NULL FROM guide_activity
                WHERE user_id = :user AND content_id = :id AND month_start = :month
                """).param("user", user).param("id", id).param("month", month).query(Boolean.class).single();
        return new Detail(content, completed);
    }

    @Transactional
    Detail complete(UUID user, UUID attempt, String id, boolean completed, Integer revision) {
        var month = LocalDate.now(KST).withDayOfMonth(1);
        var content = recordView(user, attempt, id, month);
        if (revision != null && content.version() != revision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        db.sql("""
                UPDATE guide_activity SET completed_at =
                    CASE WHEN :completed THEN clock_timestamp() ELSE NULL END,
                    last_completion_revision=:revision,last_completion_type=:type
                WHERE user_id=:user AND content_id=:id AND month_start=:month
                    AND (completed_at IS NOT NULL)<>:completed
                """).param("user", user).param("id", id).param("month", month)
                .param("revision", content.version()).param("type", content.type()).param("completed", completed).update();
        return new Detail(content, completed);
    }

    private Content recordView(UUID user, UUID attempt, String id, LocalDate month) {
        db.sql("SELECT id FROM app_user WHERE id = :user AND status = 'ACTIVE' FOR UPDATE")
                .param("user", user).query(UUID.class).optional().orElseThrow(GuideService::missing);
        var diagnosis = diagnosis(user, attempt);
        var content = item(diagnosis.type(), id);
        db.sql("""
                INSERT INTO guide_activity(user_id,content_id,month_start,content_version,representative_type,title)
                VALUES (:user,:id,:month,:version,:type,:title) ON CONFLICT DO NOTHING
                """).param("user", user).param("id", id).param("month", month)
                .param("version", content.version()).param("type", content.type()).param("title", content.title()).update();
        return content;
    }

    private Diagnosis diagnosis(UUID user, UUID attempt) {
        var query = db.sql("""
                SELECT a.id,d.representative_type,
                    round((d.score_snapshot #>> '{total,numerator}')::numeric /
                          (d.score_snapshot #>> '{total,denominator}')::numeric) AS score
                FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id JOIN app_user u ON u.id = a.user_id
                WHERE u.id = :user AND u.status = 'ACTIVE'
                """ + (attempt == null ? "" : " AND a.id = :attempt") + " ORDER BY d.completed_at DESC,d.id DESC LIMIT 1")
                .param("user", user);
        if (attempt != null) query.param("attempt", attempt);
        return query.query((row, index) -> new Diagnosis(row.getObject(1, UUID.class), row.getString(2), row.getInt(3)))
                .optional().orElseThrow(GuideService::missing);
    }

    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
