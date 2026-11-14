package kr.finsight.diagnosis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.finsight.comparison.ComparisonInvalidation;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import kr.finsight.notification.DiscordOutbox;

@Service
public class DiagnosisService {
    public record Attempt(UUID id, String surveyVersion, String formulaVersion, Instant startedAt) {}
    public record Diagnosis(UUID id, UUID attemptId, String surveyVersion, Instant completedAt,
                            YfsiScoring.Result result) {}
    static final class AttemptNotFound extends IllegalArgumentException {}
    static final class AttemptConflict extends IllegalStateException {
        AttemptConflict(String message) { super(message); }
    }
    private record AttemptState(String surveyVersion, String formulaVersion, boolean aborted) {}

    private final JdbcClient db;
    private final ComparisonInvalidation comparisons;
    private final DiscordOutbox discord;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    DiagnosisService(JdbcClient db, JdbcTemplate jdbc, JsonMapper json, DiscordOutbox discord, ComparisonInvalidation comparisons) {
        this.db = db;
        this.comparisons = comparisons;
        this.discord = discord;
        this.jdbc = jdbc;
        this.json = json;
    }

    public enum Milestone { Q1_VIEWED, Q15_COMPLETED }

    @Transactional
    public void enter(UUID user, UUID journey) {
        requireActiveUser(user);
        ensureJourney(user, journey);
    }

    @Transactional
    public Attempt start(UUID userId) {
        return start(userId, null);
    }

    @Transactional
    public Attempt start(UUID userId, UUID journeyId) {
        requireActiveUser(userId);
        var journey = journeyId == null ? UUID.randomUUID() : journeyId;
        ensureJourney(userId, journey);
        var existing = db.sql("""
                SELECT id,survey_version,formula_version,started_at FROM survey_attempt
                WHERE journey_id=:journey AND user_id=:user
                """).param("journey", journey).param("user", userId)
                .query((row, n) -> new Attempt(row.getObject(1, UUID.class), row.getString(2),
                        row.getString(3), row.getObject(4, OffsetDateTime.class).toInstant())).optional();
        if (existing.isPresent()) return existing.get();
        var attempt = new Attempt(UUID.randomUUID(), SurveyCatalog.SURVEY_VERSION,
                YfsiScoring.FORMULA_VERSION, Instant.now());
        db.sql("""
                INSERT INTO survey_attempt (id, user_id, survey_version, formula_version, started_at, journey_id)
                VALUES (:id, :user, :survey, :formula, :started, :journey)
                """)
                .param("id", attempt.id()).param("user", userId).param("survey", attempt.surveyVersion())
                .param("formula", attempt.formulaVersion()).param("started", utc(attempt.startedAt()))
                .param("journey", journey).update();
        return attempt;
    }

    private void ensureJourney(UUID user, UUID journey) {
        db.sql("""
                INSERT INTO diagnosis_journey(id,user_id)
                SELECT :id,:user WHERE NOT EXISTS (SELECT 1 FROM deleted_journey WHERE digest=sha256(uuid_send(:id)))
                ON CONFLICT DO NOTHING
                """)
                .param("id", journey).param("user", user).update();
        if (!db.sql("SELECT EXISTS(SELECT 1 FROM diagnosis_journey WHERE id=:id AND user_id=:user)")
                .param("id", journey).param("user", user).query(Boolean.class).single()) {
            throw new AttemptNotFound();
        }
    }

    @Transactional
    public void recordMilestone(UUID user, UUID attempt, Milestone kind) {
        requireActiveUser(user);
        lockAttempt(user, attempt);
        insertMilestone(attempt, kind);
    }

    private void insertMilestone(UUID attempt, Milestone kind) {
        db.sql("""
                INSERT INTO diagnosis_milestone(attempt_id,kind) VALUES (:attempt,:kind)
                ON CONFLICT DO NOTHING
                """).param("attempt", attempt).param("kind", kind.name()).update();
    }

    public enum QuestionEventKind { VIEWED, COMPLETED }

    @Transactional
    public void recordQuestionEvent(UUID user, UUID attempt, UUID event, int question, QuestionEventKind kind,
                                    Integer activeMs, OffsetDateTime occurredAt) {
        requireActiveUser(user);
        lockAttempt(user, attempt);
        var inserted = db.sql("""
                INSERT INTO diagnosis_question_event(id,attempt_id,question_no,kind,active_ms,client_occurred_at)
                VALUES (:id,:attempt,:question,:kind,:active,:occurred) ON CONFLICT DO NOTHING
                """).param("id", event).param("attempt", attempt).param("question", question)
                .param("kind", kind.name()).param("active", activeMs, java.sql.Types.INTEGER)
                .param("occurred", occurredAt, java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
        if (inserted == 0) return;
        if (question == 1 && kind == QuestionEventKind.VIEWED) insertMilestone(attempt, Milestone.Q1_VIEWED);
        if (question == 15 && kind == QuestionEventKind.COMPLETED) insertMilestone(attempt, Milestone.Q15_COMPLETED);
    }

    @Transactional
    public void abort(UUID userId, UUID attemptId, int lastQuestion) {
        if (lastQuestion < 1 || lastQuestion > YfsiScoring.QUESTION_COUNT) {
            throw new IllegalArgumentException("마지막 문항 번호는 1~30이어야 합니다.");
        }
        requireActiveUser(userId);
        var state = lockAttempt(userId, attemptId);
        if (findByAttempt(userId, attemptId).isPresent()) {
            throw new AttemptConflict("완료된 진단은 중단할 수 없습니다.");
        }
        if (!state.aborted()) {
            db.sql("UPDATE survey_attempt SET aborted_at = now(), last_question = :question WHERE id = :id")
                    .param("question", lastQuestion).param("id", attemptId).update();
        }
    }

    @Transactional
    public Diagnosis complete(UUID userId, UUID attemptId, List<Integer> answers) {
        requireActiveUser(userId);
        var state = lockAttempt(userId, attemptId);
        var existing = findByAttempt(userId, attemptId);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (state.aborted()) {
            throw new AttemptConflict("중단된 진단은 제출할 수 없습니다.");
        }
        if (!SurveyCatalog.SURVEY_VERSION.equals(state.surveyVersion())
                || !YfsiScoring.FORMULA_VERSION.equals(state.formulaVersion())) {
            throw new AttemptConflict("진단을 새로 시작해주세요.");
        }

        var result = YfsiScoring.calculate(answers);
        var diagnosis = new Diagnosis(UUID.randomUUID(), attemptId, state.surveyVersion(), Instant.now(), result);
        db.sql("""
                INSERT INTO diagnosis (id, attempt_id, completed_at, representative_type, score_snapshot, profile_snapshot)
                VALUES (:id, :attempt, :completed, :type, CAST(:snapshot AS jsonb),
                        (SELECT attributes FROM app_user_profile WHERE user_id=:user))
                """)
                .param("id", diagnosis.id()).param("attempt", attemptId).param("user", userId)
                .param("completed", utc(diagnosis.completedAt())).param("type", result.representativeType().name())
                .param("snapshot", json.writeValueAsString(result)).update();
        jdbc.batchUpdate("""
                INSERT INTO diagnosis_answer (diagnosis_id, question_no, response) VALUES (?, ?, ?)
                """, IntStream.range(0, answers.size())
                .mapToObj(index -> new Object[]{diagnosis.id(), index + 1, answers.get(index)}).toList());
        comparisons.diagnosisCompleted();
        discord.surveyCompleted(diagnosis.id(), userId);
        return findByAttempt(userId, attemptId).orElseThrow();
    }

    @Transactional
    public void recordView(UUID user, UUID attempt, UUID event, String content, Integer revision, OffsetDateTime occurredAt) {
        requireActiveUser(user);
        var type = db.sql("""
                SELECT d.representative_type FROM diagnosis d JOIN survey_attempt a ON a.id=d.attempt_id
                WHERE a.id=:attempt AND a.user_id=:user
                """).param("attempt", attempt).param("user", user).query(String.class).optional()
                .orElseThrow(AttemptNotFound::new);
        Integer viewedRevision = null;
        if (content != null) {
            viewedRevision = db.sql("""
                    SELECT p.revision_no FROM published_guide p
                    WHERE p.content_key=:content AND :type=ANY(p.types)
                        AND (CAST(:revision AS integer) IS NULL OR p.revision_no=:revision)
                        AND EXISTS (SELECT 1 FROM guide_activity g WHERE g.user_id=:user AND g.content_id=:content)
                    """).param("content", content).param("type", type).param("revision", revision, java.sql.Types.INTEGER)
                    .param("user", user).query(Integer.class).optional().orElseThrow(AttemptNotFound::new);
        } else if (revision != null) {
            throw new AttemptNotFound();
        }
        db.sql("""
                INSERT INTO diagnosis_view_event(id,attempt_id,content_id,content_revision,client_occurred_at)
                VALUES (:id,:attempt,:content,:revision,:occurred) ON CONFLICT (id) DO NOTHING
                """).param("id", event).param("attempt", attempt).param("content", content, java.sql.Types.VARCHAR)
                .param("revision", viewedRevision, java.sql.Types.INTEGER)
                .param("occurred", occurredAt, java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    public record HistoryItem(UUID id, UUID attemptId, Instant completedAt, int score,
                              YfsiScoring.Pressure representativeType) {}
    public record HistoryPage(List<HistoryItem> items, boolean hasMore) {}
    public record WidgetSummary(int streakMonths, LocalDate lastDiagnosisDate, LocalDate nextDiagnosisDate,
                                Integer daysUntilNext, Integer score, YfsiScoring.Pressure representativeType,
                                Integer maximumPressureScore) {}

    public WidgetSummary widgetSummary(UUID userId, boolean showScore, boolean showType) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var today = now.atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate();
        return db.sql("""
                WITH completed AS MATERIALIZED (
                    SELECT d.id,d.completed_at,d.representative_type,d.score_snapshot
                    FROM diagnosis d JOIN survey_attempt a ON a.id=d.attempt_id
                    JOIN app_user u ON u.id=a.user_id
                    WHERE a.user_id=:user AND u.status='ACTIVE' AND d.completed_at<=:now
                ), months AS (
                    SELECT DISTINCT date_trunc('month',completed_at AT TIME ZONE 'Asia/Seoul') AS month FROM completed
                ), ordered AS (
                    SELECT month,row_number() OVER (ORDER BY month DESC) AS position FROM months
                ), streak AS (
                    SELECT count(*) AS size FROM ordered
                    WHERE (SELECT max(month) FROM months) >= CAST(:previous AS date)
                      AND month=(SELECT max(month) FROM months) - (position - 1) * interval '1 month'
                ), latest AS (
                    SELECT (completed_at AT TIME ZONE 'Asia/Seoul')::date AS last_date,
                           CASE WHEN :showScore THEN score_snapshot->'total' END AS score,
                           CASE WHEN :showType THEN representative_type END AS representative_type,
                           CASE WHEN :showScore AND :showType
                                THEN score_snapshot #> '{rankedPressures,0,score}' END AS maximum_pressure_score
                    FROM completed ORDER BY completed_at DESC,id DESC LIMIT 1
                )
                SELECT streak.size,latest.* FROM streak LEFT JOIN latest ON true
                """).param("user", userId).param("now", now)
                .param("previous", today.withDayOfMonth(1).minusMonths(1))
                .param("showScore", showScore).param("showType", showType)
                .query((row, index) -> {
                    var last = row.getObject("last_date", LocalDate.class);
                    var next = last == null ? null : last.plusDays(30);
                    var score = row.getString("score");
                    var type = row.getString("representative_type");
                    var pressure = row.getString("maximum_pressure_score");
                    return new WidgetSummary(row.getInt("size"), last, next,
                            next == null ? null : Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(today, next)),
                            score == null ? null : json.readValue(score, YfsiScoring.Score.class).display(),
                            type == null ? null : YfsiScoring.Pressure.valueOf(type),
                            pressure == null ? null : json.readValue(pressure, YfsiScoring.Score.class).display());
                }).single();
    }

    public HistoryPage history(UUID userId, OffsetDateTime before, UUID beforeId) {
        var query = db.sql("""
                SELECT d.id, d.attempt_id, d.completed_at, d.representative_type, d.score_snapshot -> 'total' AS score
                FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                JOIN app_user u ON u.id = a.user_id
                WHERE u.id = :user AND u.status = 'ACTIVE'
                """ + (before == null ? "" : " AND (d.completed_at, d.id) < (:before, :beforeId)") + """
                ORDER BY d.completed_at DESC, d.id DESC
                LIMIT 21
                """).param("user", userId);
        if (before != null) query.param("before", before).param("beforeId", beforeId);
        var rows = query.query(this::historyItem).list();
        return new HistoryPage(rows.stream().limit(20).toList(), rows.size() > 20);
    }

    public List<HistoryItem> trend(UUID userId) {
        var kst = ZoneId.of("Asia/Seoul");
        var today = LocalDate.now(kst);
        var cutoff = today.minusMonths(6).atStartOfDay(kst).toOffsetDateTime();
        return db.sql("""
                SELECT DISTINCT ON ((d.completed_at AT TIME ZONE 'Asia/Seoul')::date)
                    d.id, d.attempt_id, d.completed_at, d.representative_type, d.score_snapshot -> 'total' AS score
                FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                JOIN app_user u ON u.id = a.user_id
                WHERE u.id = :user AND u.status = 'ACTIVE' AND d.completed_at >= :cutoff
                ORDER BY (d.completed_at AT TIME ZONE 'Asia/Seoul')::date DESC, d.completed_at DESC, d.id DESC
                """).param("user", userId).param("cutoff", cutoff).query(this::historyItem).list();
    }

    private HistoryItem historyItem(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new HistoryItem(
                row.getObject("id", UUID.class), row.getObject("attempt_id", UUID.class),
                row.getObject("completed_at", OffsetDateTime.class).toInstant(),
                json.readValue(row.getString("score"), YfsiScoring.Score.class).display(),
                YfsiScoring.Pressure.valueOf(row.getString("representative_type"))
        );
    }

    public Optional<Diagnosis> findByAttempt(UUID userId, UUID attemptId) {
        return db.sql("""
                SELECT d.id, a.id AS attempt_id, a.survey_version, d.completed_at, d.score_snapshot
                FROM diagnosis d
                JOIN survey_attempt a ON a.id = d.attempt_id
                JOIN app_user u ON u.id = a.user_id
                WHERE a.id = :attempt AND u.id = :user AND u.status = 'ACTIVE'
                """)
                .param("attempt", attemptId).param("user", userId)
                .query((row, index) -> new Diagnosis(
                        row.getObject("id", UUID.class),
                        row.getObject("attempt_id", UUID.class),
                        row.getString("survey_version"),
                        row.getObject("completed_at", OffsetDateTime.class).toInstant(),
                        json.readValue(row.getString("score_snapshot"), YfsiScoring.Result.class)
                )).optional();
    }

    private void requireActiveUser(UUID userId) {
        db.sql("SELECT id FROM app_user WHERE id = :id AND status = 'ACTIVE' FOR UPDATE")
                .param("id", userId).query(UUID.class).optional()
                .orElseThrow(() -> new AttemptConflict("진단을 이용할 수 없는 계정입니다."));
    }

    private AttemptState lockAttempt(UUID userId, UUID attemptId) {
        return db.sql("""
                SELECT survey_version, formula_version, aborted_at IS NOT NULL AS aborted
                FROM survey_attempt WHERE id = :attempt AND user_id = :user FOR UPDATE
                """)
                .param("attempt", attemptId).param("user", userId)
                .query((row, index) -> new AttemptState(
                        row.getString("survey_version"), row.getString("formula_version"), row.getBoolean("aborted")))
                .optional().orElseThrow(() -> new AttemptNotFound());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
