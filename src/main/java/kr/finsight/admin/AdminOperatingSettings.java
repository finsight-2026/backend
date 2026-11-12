package kr.finsight.admin;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.finsight.comparison.ComparisonPublishing;
import kr.finsight.diagnosis.YfsiScoring;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local")
class AdminOperatingSettings {
    record Policy(long version, List<String> comparisonMetrics, boolean crmPaused, boolean automaticPushPaused,
                  int reminderCap, int totalCap, BigDecimal funnelCheck, BigDecimal funnelPriority, UUID operatorId,
                  OffsetDateTime updatedAt) {}
    record Operator(UUID id, String name) {}
    record Settings(Policy policy, List<ComparisonPublishing.Settings> comparisonMinimums, List<Operator> operators) {}
    record Minimum(YfsiScoring.Pressure type, Integer minimum) {}
    record Changes(List<String> comparisonMetrics, List<Minimum> comparisonMinimums, Boolean crmPaused,
                   Boolean automaticPushPaused, Integer reminderCap, Integer totalCap, BigDecimal funnelCheck,
                   BigDecimal funnelPriority, JsonNode operatorId) {}
    record Update(Changes changes, Long expectedVersion, String reason) {}
    record FunnelLimits(BigDecimal check, BigDecimal priority) {}

    private static final Set<String> METRICS = Set.of("YFSI_MEAN", "EXTERNAL_AREA_MEAN",
            "RESOURCE_DOMAIN_MEAN", "PERCEIVED_STRESS_MEAN");
    private static final String POLICY_FIELDS = """
            SELECT version,comparison_metrics,crm_paused,automatic_push_paused,reminder_cap,total_cap,
                   funnel_check,funnel_priority,operator_id,updated_at FROM operating_settings WHERE singleton
            """;

    private final AdminAuth auth;
    private final JdbcClient db;
    private final ComparisonPublishing comparisons;
    private final AdminMutationJournal journal;
    private final JsonMapper json;

    AdminOperatingSettings(AdminAuth auth, JdbcClient db, ComparisonPublishing comparisons, AdminMutationJournal journal, JsonMapper json) {
        this.auth = auth;
        this.db = db;
        this.comparisons = comparisons;
        this.journal = journal;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Settings read(AdminAuth.Identity actor) {
        requireSupervisor(actor);
        return snapshot(policy(false), comparisons.settings());
    }

    @Transactional
    Settings update(AdminAuth.Identity actor, String session, Update request) {
        auth.lockVerifiedSupervisor(actor, session);
        if (request == null || request.changes() == null || request.expectedVersion() == null) throw invalid();
        String reason = request.reason() == null ? "" : request.reason().strip();
        int length = reason.codePointCount(0, reason.length());
        if (length < 10 || length > 500) throw invalid();

        var before = policy(true);
        if (before.version() != request.expectedVersion()) throw new ResponseStatusException(HttpStatus.CONFLICT);
        var input = request.changes();
        var metrics = value(input.comparisonMetrics(), before.comparisonMetrics());
        int reminder = value(input.reminderCap(), before.reminderCap());
        int total = value(input.totalCap(), before.totalCap());
        var check = value(input.funnelCheck(), before.funnelCheck());
        var priority = value(input.funnelPriority(), before.funnelPriority());
        var operator = operator(input.operatorId(), before.operatorId());
        boolean crm = value(input.crmPaused(), before.crmPaused());
        boolean auto = value(input.automaticPushPaused(), before.automaticPushPaused());
        if (metrics.stream().anyMatch(Objects::isNull) || !METRICS.containsAll(metrics)
                || metrics.stream().distinct().count() != metrics.size()
                || reminder < 0 || reminder > 1 || total < reminder || total > 2
                || check.signum() < 0 || priority.compareTo(check) < 0
                || priority.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid();
        metrics = metrics.stream().sorted().toList();

        var beforeMinimums = comparisons.settings();
        var minimumChanges = new LinkedHashMap<YfsiScoring.Pressure, Integer>();
        for (var row : value(input.comparisonMinimums(), List.<Minimum>of())) {
            if (row == null || row.type() == null || minimumChanges.containsKey(row.type())
                    || (row.minimum() != null && row.minimum() < 30)) throw invalid();
            minimumChanges.put(row.type(), row.minimum());
        }
        var changes = new LinkedHashMap<String, Object>();
        change(changes, "comparisonMetrics", before.comparisonMetrics().stream().sorted().toList(), metrics);
        change(changes, "crmPaused", before.crmPaused(), crm);
        change(changes, "automaticPushPaused", before.automaticPushPaused(), auto);
        change(changes, "reminderCap", before.reminderCap(), reminder);
        change(changes, "totalCap", before.totalCap(), total);
        change(changes, "funnelCheck", before.funnelCheck().stripTrailingZeros(), check.stripTrailingZeros());
        change(changes, "funnelPriority", before.funnelPriority().stripTrailingZeros(), priority.stripTrailingZeros());
        change(changes, "operatorId", before.operatorId(), operator);
        for (var existing : beforeMinimums) {
            var type = YfsiScoring.Pressure.valueOf(existing.type());
            if (!minimumChanges.containsKey(type)) continue;
            var minimum = minimumChanges.get(type);
            if (Objects.equals(minimum, existing.minimum())) continue;
            comparisons.updateMinimum(type, minimum, existing.version(), reason);
            change(changes, "minimum:" + type.name(), existing.minimum(), minimum);
        }
        if (changes.isEmpty()) return snapshot(before, beforeMinimums);

        db.sql("""
                UPDATE operating_settings SET version=:version,
                    comparison_metrics=ARRAY(SELECT jsonb_array_elements_text(CAST(:metrics AS jsonb))),
                    crm_resumed_at=CASE WHEN crm_paused AND NOT :crm THEN clock_timestamp() ELSE crm_resumed_at END,
                    automatic_push_resumed_at=CASE WHEN automatic_push_paused AND NOT :auto THEN clock_timestamp()
                        ELSE automatic_push_resumed_at END,
                    crm_paused=:crm,automatic_push_paused=:auto,reminder_cap=:reminder,total_cap=:total,
                    funnel_check=:check,funnel_priority=:priority,operator_id=:operator,
                    updated_by=:actor,updated_at=clock_timestamp() WHERE singleton
                """).param("version", before.version()+1).param("metrics", json.writeValueAsString(metrics))
                .param("crm", crm).param("auto", auto).param("reminder", reminder).param("total", total)
                .param("check", check).param("priority", priority).param("operator", operator, Types.OTHER)
                .param("actor", actor.admin().id()).update();
        if (changes.containsKey("comparisonMetrics")) recheckPublications();
        journal.append(actor, "OPERATING_SETTINGS_UPDATED", "OPERATING_SETTINGS", "global",
                Map.of("version", before.version()+1, "reason", reason, "changes", changes));
        return snapshot(policy(false), comparisons.settings());
    }

    FunnelLimits funnelLimits() {
        return db.sql("SELECT funnel_check,funnel_priority FROM operating_settings WHERE singleton")
                .query((row, n) -> new FunnelLimits(row.getBigDecimal(1), row.getBigDecimal(2))).single();
    }

    private Policy policy(boolean lock) {
        return db.sql(POLICY_FIELDS + (lock ? " FOR UPDATE" : ""))
                .query((row, n) -> new Policy(row.getLong(1), List.of((String[]) row.getArray(2).getArray()),
                        row.getBoolean(3), row.getBoolean(4), row.getInt(5), row.getInt(6),
                        row.getBigDecimal(7), row.getBigDecimal(8), row.getObject(9, UUID.class),
                        row.getObject(10, OffsetDateTime.class))).single();
    }

    private Settings snapshot(Policy policy, List<ComparisonPublishing.Settings> minimums) {
        var operators = db.sql("""
                SELECT a.id,a.name FROM admin_account a JOIN admin_role r ON r.admin_id=a.id
                WHERE a.status='ACTIVE' AND r.role='OPERATOR' ORDER BY a.name,a.id
                """).query(Operator.class).list();
        return new Settings(policy, minimums, operators);
    }

    private UUID operator(JsonNode value, UUID current) {
        if (value == null) return current;
        if (value.isNull()) return null;
        if (!value.isString()) throw invalid();
        UUID id;
        try {
            id = UUID.fromString(value.asString());
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
        if (db.sql("""
                SELECT a.id FROM admin_account a JOIN admin_role r ON r.admin_id=a.id
                WHERE a.id=:id AND a.status='ACTIVE' AND r.role='OPERATOR' FOR SHARE OF a,r
                """).param("id", id).query(UUID.class).optional().isEmpty()) throw invalid();
        return id;
    }

    private void recheckPublications() {
        db.sql("""
                UPDATE comparison_publication p SET status='INVALIDATED',version=p.version+1
                FROM operating_settings o WHERE o.singleton AND p.status IN ('ACTIVE','SCHEDULED')
                AND NOT (p.allowed_metrics<@o.comparison_metrics)
                """).update();
        db.sql("""
                UPDATE comparison_snapshot s SET status='IN_REVIEW',version=s.version+1,
                    approved_by=NULL,approved_metrics=NULL,approved_start=NULL,approved_end=NULL,approval_reason=NULL
                FROM operating_settings o WHERE o.singleton AND s.status='APPROVED'
                AND NOT (s.approved_metrics<@o.comparison_metrics)
                """).update();
    }

    private static void change(Map<String, Object> changes, String key, Object before, Object after) {
        if (Objects.equals(before, after)) return;
        var pair = new LinkedHashMap<String, Object>();
        pair.put("before", before);
        pair.put("after", after);
        changes.put(key, pair);
    }

    private static <T> T value(T input, T current) {
        return input == null ? current : input;
    }

    private static void requireSupervisor(AdminAuth.Identity actor) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!actor.permissions().contains("SUPER_ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
