package kr.finsight.admin;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class AdminSegments {
    record Range(LocalDate from, LocalDate to) {}
    record Conditions(List<YfsiScoring.Pressure> types, List<String> ages, Integer minDiagnoses, Integer maxDiagnoses,
                      Range lastDiagnosis, Range signup, Range activity, List<UUID> tagIds) {}
    record Fields(String name, String description, Conditions conditions) {}
    record Segment(UUID id, String name, String description, String status, int definitionVersion, long version,
                   Conditions conditions, OffsetDateTime updatedAt) {}
    record Preview(long total, long consented, long deviceCount, long eligible, long localEligible,
                   Map<String, Long> excludedByReason, OffsetDateTime asOf, int definitionVersion) {}

    record CandidateQuery(String sql, Map<String, Object> values) {}

    private static final String SELECT = """
            SELECT s.id,s.name,s.description,s.status,s.current_version,s.version,r.conditions,s.updated_at
            FROM segment s JOIN segment_revision r ON r.segment_id=s.id AND r.revision_no=s.current_version
            """;
    private final JdbcClient db;
    private final AdminMutationJournal journal;
    private final JsonMapper json;

    AdminSegments(JdbcClient db, AdminMutationJournal journal, JsonMapper json) {
        this.db = db;
        this.journal = journal;
        this.json = json;
    }

    List<Segment> list() {
        return db.sql(SELECT + " ORDER BY s.updated_at DESC,s.id DESC").query(this::read).list();
    }

    Segment create(AdminAuth.Identity actor, Fields input) {
        var fields = validate(input);
        var id = UUID.randomUUID();
        return journal.record(actor, "SEGMENT_CREATED", "SEGMENT", id.toString(), () -> {
            db.sql("""
                    INSERT INTO segment(id,name,description,status,current_version) VALUES (:id,:name,:description,'ACTIVE',1)
                    """).param("id", id).param("name", fields.name()).param("description", fields.description()).update();
            revision(actor, id, 1, fields.conditions());
            return find(id);
        }, item -> Map.of("definitionVersion", item.definitionVersion(), "version", item.version()));
    }

    Segment update(AdminAuth.Identity actor, UUID id, Fields input, long expected) {
        var fields = validate(input);
        return journal.record(actor, "SEGMENT_UPDATED", "SEGMENT", id.toString(), () -> {
            var next = db.sql("""
                    UPDATE segment SET name=:name,description=:description,current_version=current_version+1,
                        version=version+1,updated_at=clock_timestamp()
                    WHERE id=:id AND status='ACTIVE' AND version=:expected RETURNING current_version
                    """).param("id", id).param("name", fields.name()).param("description", fields.description())
                    .param("expected", expected).query(Integer.class).optional().orElseThrow(AdminSegments::conflict);
            revision(actor, id, next, fields.conditions());
            return find(id);
        }, item -> Map.of("definitionVersion", item.definitionVersion(), "version", item.version()));
    }

    void archive(AdminAuth.Identity actor, UUID id, long expected) {
        journal.record(actor, "SEGMENT_ARCHIVED", "SEGMENT", id.toString(), () -> {
            return db.sql("""
                    UPDATE segment SET status='ARCHIVED',version=version+1,updated_at=clock_timestamp()
                    WHERE id=:id AND status='ACTIVE' AND version=:expected RETURNING version
                    """).param("id", id).param("expected", expected).query(Long.class).optional().orElseThrow(AdminSegments::conflict);
        }, version -> Map.of("version", version));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Preview preview(UUID id) {
        var segment = find(id);
        if (!segment.status().equals("ACTIVE")) throw conflict();
        var now = OffsetDateTime.now(AdminUsers.KST);
        return count(candidates(segment.conditions(), now.toLocalDate()), segment.definitionVersion(), now, null);
    }

    int activeRevision(UUID id) {
        var item = find(id);
        if (!item.status().equals("ACTIVE")) throw conflict();
        return item.definitionVersion();
    }

    CandidateQuery revisionCandidates(UUID id, int revision, LocalDate day) {
        var condition = db.sql("SELECT conditions FROM segment_revision WHERE segment_id=:id AND revision_no=:version")
                .param("id", id).param("version", revision)
                .query((row, n) -> json.readValue(row.getString(1), Conditions.class)).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return candidates(condition, day);
    }

    private CandidateQuery candidates(Conditions condition, LocalDate day) {
        var where = new ArrayList<String>();
        var values = new LinkedHashMap<String, Object>();
        array(where, values, "diagnosis.type", "types", condition.types());
        array(where, values, "p.attributes->>'age'", "ages", condition.ages());
        if (condition.minDiagnoses() != null) {
            where.add("diagnosis.total>=:minimum");
            values.put("minimum", condition.minDiagnoses());
        }
        if (condition.maxDiagnoses() != null) {
            where.add("diagnosis.total<=:maximum");
            values.put("maximum", condition.maxDiagnoses());
        }
        range(where, values, "(diagnosis.completed_at AT TIME ZONE 'Asia/Seoul')::date", "diagnosis", condition.lastDiagnosis());
        range(where, values, "(u.created_at AT TIME ZONE 'Asia/Seoul')::date", "signup", condition.signup());
        range(where, values, "activity.last_day", "activity", condition.activity());
        if (!condition.tagIds().isEmpty()) {
            where.add("""
                    EXISTS(SELECT 1 FROM user_tag ut JOIN tag t ON t.id=ut.tag_id
                        WHERE ut.user_id=u.id AND t.status='ACTIVE'
                        AND t.id IN (SELECT value::uuid FROM jsonb_array_elements_text(CAST(:tags AS jsonb))))
                    """);
            values.put("tags", json.writeValueAsString(condition.tagIds()));
        }

        values.put("day", day);
        return new CandidateQuery("""
                    SELECT u.id,u.status,coalesce(pref.enabled,false) AS consent,
                           NOT notification_slot_available(u.id,:day,'REMINDER') AS capped
                    FROM app_user u
                    LEFT JOIN app_user_profile p ON p.user_id=u.id
                    LEFT JOIN app_notification_preference pref ON pref.user_id=u.id
                    CROSS JOIN LATERAL (
                        SELECT count(*) AS total,max(d.completed_at) AS completed_at,
                            (array_agg(d.representative_type ORDER BY d.completed_at DESC,d.id DESC))[1] AS type
                        FROM survey_attempt a JOIN diagnosis d ON d.attempt_id=a.id WHERE a.user_id=u.id
                    ) diagnosis
                    CROSS JOIN LATERAL (
                        SELECT max(activity_date) AS last_day FROM app_foreground_day WHERE user_id=u.id
                    ) activity
                    WHERE u.status<>'DATA_DELETION_PENDING'
                """ + (where.isEmpty() ? "" : " AND " + String.join(" AND ", where)), values);
    }

    Preview count(CandidateQuery candidates, int definitionVersion, OffsetDateTime execution, UUID notice) {
        return db.sql("WITH candidates AS (" + candidates.sql() + """
                ), recipient_status AS (
                    SELECT c.*,
                        EXISTS(SELECT 1 FROM app_session s JOIN app_push_registration r ON r.session_id=s.id
                            WHERE s.user_id=c.id AND s.expires_at>:execution) AS has_device,
                        EXISTS(SELECT 1 FROM app_notification n
                            WHERE n.user_id=c.id AND n.kind='NOTICE' AND n.event_key=:notice) AS duplicate
                    FROM candidates c
                ), eligibility AS (
                    SELECT *,status='ACTIVE' AND consent AND NOT duplicate AND NOT capped AS eligible
                    FROM recipient_status
                )
                SELECT count(*) AS total,count(*) FILTER(WHERE consent) AS consented,
                    count(*) FILTER(WHERE status='ACTIVE' AND has_device) AS device_count,
                    count(*) FILTER(WHERE eligible AND has_device) AS eligible,
                    count(*) FILTER(WHERE eligible) AS inbox_count,
                    count(*) FILTER(WHERE status<>'ACTIVE') AS withdrawal,
                    count(*) FILTER(WHERE status='ACTIVE' AND NOT consent) AS opted_out,
                    count(*) FILTER(WHERE status='ACTIVE' AND consent AND duplicate) AS duplicate,
                    count(*) FILTER(WHERE status='ACTIVE' AND consent AND NOT duplicate AND capped) AS capped,
                    count(*) FILTER(WHERE eligible AND NOT has_device) AS no_device,clock_timestamp() AS as_of
                FROM eligibility
                """).params(candidates.values()).param("execution", execution)
                .param("notice", notice == null ? null : notice.toString(), java.sql.Types.VARCHAR)
                .query((row, n) -> {
                    var excluded = new LinkedHashMap<String, Long>();
                    excluded.put("WITHDRAWAL_PENDING", row.getLong("withdrawal"));
                    excluded.put("OPTED_OUT", row.getLong("opted_out"));
                    excluded.put("DAILY_CAP", row.getLong("capped"));
                    if (notice != null) excluded.put("DUPLICATE_NOTICE", row.getLong("duplicate"));
                    excluded.put("NO_DEVICE", row.getLong("no_device"));
                    return new Preview(row.getLong("total"), row.getLong("consented"), row.getLong("device_count"),
                            row.getLong("eligible"), row.getLong("inbox_count"), excluded,
                            row.getObject("as_of", OffsetDateTime.class), definitionVersion);
                }).single();
    }

    private Segment find(UUID id) {
        return db.sql(SELECT + " WHERE s.id=:id").param("id", id).query(this::read).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Segment read(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new Segment(row.getObject(1, UUID.class), row.getString(2), row.getString(3), row.getString(4),
                row.getInt(5), row.getLong(6), json.readValue(row.getString(7), Conditions.class),
                row.getObject(8, OffsetDateTime.class));
    }

    private void revision(AdminAuth.Identity actor, UUID id, int version, Conditions condition) {
        db.sql("""
                INSERT INTO segment_revision(segment_id,revision_no,conditions,created_by)
                VALUES (:id,:version,CAST(:condition AS jsonb),:actor)
                """).param("id", id).param("version", version).param("condition", json.writeValueAsString(condition))
                .param("actor", actor.admin().id()).update();
    }

    private void array(List<String> where, Map<String, Object> values, String field, String key, List<?> items) {
        if (items.isEmpty()) return;
        where.add(field + " IN (SELECT value FROM jsonb_array_elements_text(CAST(:" + key + " AS jsonb)))");
        values.put(key, json.writeValueAsString(items));
    }

    private static void range(List<String> where, Map<String, Object> values, String field, String key, Range range) {
        if (range == null) return;
        if (range.from() != null) {
            where.add(field + ">=:" + key + "From");
            values.put(key + "From", range.from());
        }
        if (range.to() != null) {
            where.add(field + "<=:" + key + "To");
            values.put(key + "To", range.to());
        }
    }

    private static Fields validate(Fields input) {
        if (input == null || input.name() == null || input.conditions() == null) throw invalid();
        var name = input.name().strip();
        var description = input.description() == null ? "" : input.description().strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 50
                || description.codePointCount(0, description.length()) > 300) throw invalid();
        var condition = input.conditions();
        var types = selections(condition.types(), YfsiScoring.Pressure.values().length);
        var ages = selections(condition.ages(), AdminUsers.AGE_CODES.size());
        var tags = selections(condition.tagIds(), Integer.MAX_VALUE);
        if (!AdminUsers.AGE_CODES.containsAll(ages)
                || (condition.minDiagnoses() != null && condition.minDiagnoses() < 0)
                || (condition.maxDiagnoses() != null && condition.maxDiagnoses() < 0)
                || (condition.minDiagnoses() != null && condition.maxDiagnoses() != null
                    && condition.minDiagnoses() > condition.maxDiagnoses())
                || reversed(condition.lastDiagnosis()) || reversed(condition.signup()) || reversed(condition.activity())) throw invalid();
        return new Fields(name, description, new Conditions(types, ages, condition.minDiagnoses(), condition.maxDiagnoses(),
                condition.lastDiagnosis(), condition.signup(), condition.activity(), tags));
    }

    private static <T> List<T> selections(List<T> values, int max) {
        if (values == null) return List.of();
        if (values.size() > max || values.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        return values.stream().distinct().toList();
    }

    private static boolean reversed(Range range) {
        return range != null && range.from() != null && range.to() != null && range.from().isAfter(range.to());
    }

    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
}
