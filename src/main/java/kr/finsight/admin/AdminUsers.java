package kr.finsight.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class AdminUsers {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Set<String> AGE_CODES = Set.of("19_22", "23_25", "26_29", "30_PLUS");
    enum Status { ACTIVE, WITHDRAWAL_PENDING }
    record Filters(UUID userId, UUID diagnosisId, String type, String age, Status status,
                   UUID tagId, LocalDate from, LocalDate to, boolean includeNoDiagnosis, int page, int size) {}
    record User(UUID id, String maskedEmail, OffsetDateTime createdAt, Status status,
                OffsetDateTime lastDiagnosisAt, String representativeType, long diagnosisCount, List<String> tags) {}
    record Page<T>(List<T> items, int page, int size, long total) {}
    record Consent(String documentKind, String documentVersion, boolean granted, OffsetDateTime recordedAt) {}
    record DiagnosisSummary(UUID id, OffsetDateTime completedAt, String representativeType) {}
    record Detail(User user, Map<String, String> profile, List<Consent> consents,
                  List<DiagnosisSummary> recentDiagnoses) {}
    record Diagnosis(UUID id, OffsetDateTime completedAt, String representativeType, String surveyVersion,
                     String formulaVersion, JsonNode score) {}

    private static final String USER_COLUMNS = """
            u.id, u.email, u.created_at, CASE WHEN u.status='DATA_DELETION_PENDING' THEN 'ACTIVE' ELSE u.status END AS status,
            latest.completed_at, latest.representative_type,
            (SELECT count(*) FROM survey_attempt ac JOIN diagnosis dc ON dc.attempt_id = ac.id
                WHERE ac.user_id = u.id AND u.status<>'DATA_DELETION_PENDING') AS diagnosis_count,
            ARRAY(SELECT t.name FROM user_tag ut JOIN tag t ON t.id = ut.tag_id
                WHERE ut.user_id = u.id AND t.status = 'ACTIVE' ORDER BY t.name, t.id) AS tags
            """;
    private static final String USER_SOURCE = """
            FROM app_user u
            LEFT JOIN app_user_profile p ON p.user_id = u.id
            LEFT JOIN LATERAL (
                SELECT d.completed_at, d.representative_type FROM survey_attempt a
                JOIN diagnosis d ON d.attempt_id = a.id WHERE a.user_id = u.id AND u.status<>'DATA_DELETION_PENDING'
                ORDER BY d.completed_at DESC, d.id DESC LIMIT 1
            ) latest ON true
            """;

    private final JdbcClient db;
    private final JsonMapper json;

    AdminUsers(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    private record FilterQuery(String source, Map<String, Object> parameters) {}

    private FilterQuery filterQuery(Filters filters) {
        var where = new ArrayList<String>();
        var parameters = new LinkedHashMap<String, Object>();
        add(where, parameters, "u.id = :userId", "userId", filters.userId());
        add(where, parameters, "p.attributes ->> 'age' = :age", "age", filters.age());
        add(where, parameters, "(CASE WHEN u.status='DATA_DELETION_PENDING' THEN 'ACTIVE' ELSE u.status END)=:status",
                "status", filters.status() == null ? null : filters.status().name());
        add(where, parameters, """
                EXISTS (SELECT 1 FROM user_tag ut JOIN tag t ON t.id = ut.tag_id
                    WHERE ut.user_id = u.id AND t.id = :tagId AND t.status = 'ACTIVE')
                """, "tagId", filters.tagId());
        parameters.put("from", filters.from().atStartOfDay(KST).toOffsetDateTime());
        parameters.put("until", filters.to().plusDays(1).atStartOfDay(KST).toOffsetDateTime());
        var diagnosis = new ArrayList<>(List.of("a.user_id = u.id", "u.status<>'DATA_DELETION_PENDING'",
                "d.completed_at >= :from", "d.completed_at < :until"));
        add(diagnosis, parameters, "d.id = :diagnosisId", "diagnosisId", filters.diagnosisId());
        add(diagnosis, parameters, "d.representative_type = :type", "type", filters.type());
        var exists = """
                EXISTS (SELECT 1 FROM survey_attempt a JOIN diagnosis d ON d.attempt_id = a.id WHERE
                """ + String.join(" AND ", diagnosis) + ")";
        if (filters.includeNoDiagnosis() && filters.type() == null && filters.diagnosisId() == null) {
            exists = "(" + exists + " OR latest.completed_at IS NULL)";
        }
        where.add(exists);
        return new FilterQuery(USER_SOURCE + " WHERE " + String.join(" AND ", where), parameters);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Page<User> list(Filters filters) {
        var query = filterQuery(filters);
        var source = query.source();
        var parameters = query.parameters();
        long total = db.sql("SELECT count(*) " + source).params(parameters).query(Long.class).single();
        var items = db.sql("SELECT " + USER_COLUMNS + source + """
                ORDER BY latest.completed_at DESC NULLS LAST, u.id
                LIMIT :size OFFSET :offset
                """).params(parameters).param("size", filters.size())
                .param("offset", (long) (filters.page() - 1) * filters.size())
                .query(AdminUsers::user).list();
        return new Page<>(items, filters.page(), filters.size(), total);
    }

    long snapshotMatches(UUID preview, Filters filters) {
        var query = filterQuery(filters);
        return db.sql("""
                INSERT INTO bulk_tag_target(preview_id,user_id) SELECT :preview,u.id
                """ + query.source()).params(query.parameters()).param("preview", preview).update();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Optional<Detail> detail(UUID id) {
        return db.sql("SELECT " + USER_COLUMNS + ", p.attributes AS profile " + USER_SOURCE + " WHERE u.id = :id")
                .param("id", id).query((row, index) -> new Detail(user(row, index),
                        safeProfile(row.getString("profile")), List.of(), List.of())).optional()
                .map(detail -> new Detail(detail.user(), detail.profile(), consents(id), recentDiagnoses(id)));
    }

    Optional<Diagnosis> diagnosis(UUID userId, UUID diagnosisId) {
        return db.sql("""
                SELECT d.id, d.completed_at, d.representative_type, a.survey_version, a.formula_version,
                    d.score_snapshot
                FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                JOIN app_user u ON u.id=a.user_id
                WHERE a.user_id = :user AND d.id = :diagnosis AND u.status<>'DATA_DELETION_PENDING'
                """).param("user", userId).param("diagnosis", diagnosisId)
                .query((row, index) -> new Diagnosis(row.getObject(1, UUID.class),
                        row.getObject(2, OffsetDateTime.class), row.getString(3), row.getString(4),
                        row.getString(5), json.readTree(row.getString(6)))).optional();
    }

    private List<DiagnosisSummary> recentDiagnoses(UUID id) {
        return db.sql("""
                SELECT d.id, d.completed_at, d.representative_type
                FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                JOIN app_user u ON u.id=a.user_id
                WHERE a.user_id = :id AND u.status<>'DATA_DELETION_PENDING'
                ORDER BY d.completed_at DESC, d.id DESC LIMIT 20
                """).param("id", id).query((row, index) -> new DiagnosisSummary(row.getObject(1, UUID.class),
                        row.getObject(2, OffsetDateTime.class), row.getString(3))).list();
    }

    private List<Consent> consents(UUID id) {
        return db.sql("""
                SELECT document_kind,document_version,granted,recorded_at
                FROM app_user_consent WHERE user_id = :id ORDER BY recorded_at DESC, document_kind, document_version
                """).param("id", id).query((row, index) -> new Consent(row.getString(1), row.getString(2),
                row.getBoolean(3), row.getObject(4, OffsetDateTime.class))).list();
    }

    private Map<String, String> safeProfile(String value) {
        if (value == null) return Map.of();
        var profile = json.readTree(value);
        var result = new LinkedHashMap<String, String>();
        for (var field : List.of("age", "situation", "gender", "housing", "income", "debt", "debtType", "expenses")) {
            var code = profile.path(field);
            if (code.isString()) result.put(field, code.asString());
        }
        return result;
    }

    private static User user(ResultSet row, int index) throws SQLException {
        return new User(row.getObject("id", UUID.class), mask(row.getString("email")),
                row.getObject("created_at", OffsetDateTime.class), Status.valueOf(row.getString("status")),
                row.getObject("completed_at", OffsetDateTime.class), row.getString("representative_type"),
                row.getLong("diagnosis_count"), List.of((String[]) row.getArray("tags").getArray()));
    }

    static String mask(String email) {
        int separator = email.indexOf('@');
        if (separator < 2) return "***@***";
        return email.substring(0, email.offsetByCodePoints(0, 1)) + "***@***";
    }

    private static void add(List<String> clauses, Map<String, Object> parameters, String clause, String name, Object value) {
        if (value != null) {
            clauses.add(clause);
            parameters.put(name, value);
        }
    }
}
