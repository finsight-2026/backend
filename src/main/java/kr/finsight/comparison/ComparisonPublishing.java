package kr.finsight.comparison;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.finsight.diagnosis.YfsiScoring;
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
public class ComparisonPublishing {
    public enum Action { SUBMIT, APPROVE, REJECT, PUBLISH, SCHEDULE, UNPUBLISH }
    public record Change(Action action, Long expectedVersion, List<String> allowedMetrics,
                         OffsetDateTime startsAt, OffsetDateTime endsAt, String reason, UUID replacePublicationId) {}
    public record Settings(String type, Integer minimum, long version) {}
    public record Publication(UUID id, UUID snapshotId, String type, String status,
                              OffsetDateTime startsAt, OffsetDateTime endsAt) {}
    private record Dataset(long version, Integer minSample, long settingsVersion) {}
    private record Approval(UUID author, UUID approver, List<String> metrics,
                            OffsetDateTime starts, OffsetDateTime ends) {}
    private static final Set<String> METRICS = Set.of("YFSI_MEAN", "EXTERNAL_AREA_MEAN",
            "RESOURCE_DOMAIN_MEAN", "PERCEIVED_STRESS_MEAN");

    private final JdbcClient db;
    private final ComparisonBuild build;
    private final JsonMapper json;

    ComparisonPublishing(JdbcClient db, ComparisonBuild build, JsonMapper json) {
        this.db = db;
        this.build = build;
        this.json = json;
    }

    public List<Settings> settings() {
        return db.sql("SELECT type,min_sample AS minimum,settings_version AS version FROM comparison_dataset ORDER BY type")
                .query(Settings.class).list();
    }

    @Transactional
    public Settings updateMinimum(YfsiScoring.Pressure type, Integer minimum, long expectedVersion, String reason) {
        if ((minimum != null && minimum < 30) || !validReason(reason)) fail(HttpStatus.UNPROCESSABLE_ENTITY);
        db.sql("SELECT version FROM operating_settings WHERE singleton FOR UPDATE").query(Long.class).single();
        var key = type.name();
        var dataset = lock(key);
        if (dataset.settingsVersion() != expectedVersion) fail(HttpStatus.CONFLICT);
        if (java.util.Objects.equals(dataset.minSample(), minimum)) return new Settings(key, minimum, expectedVersion);
        if (minimum == null) {
            db.sql("UPDATE comparison_dataset SET min_sample = NULL, settings_version = settings_version + 1 WHERE type = :type")
                    .param("type", key).update();
        } else {
            db.sql("UPDATE comparison_dataset SET min_sample = :minimum, settings_version = settings_version + 1 WHERE type = :type")
                    .param("type", key).param("minimum", minimum).update();
        }
        db.sql("""
                UPDATE comparison_publication SET status = 'INVALIDATED', version = version + 1
                WHERE type = :type AND status IN ('ACTIVE','SCHEDULED')
                """).param("type", key).update();
        db.sql("""
                UPDATE comparison_snapshot SET status = 'IN_REVIEW', version = version + 1,
                    approved_by = NULL, approved_metrics = NULL, approved_start = NULL, approved_end = NULL,
                    approval_reason = NULL
                WHERE type = :type AND status = 'APPROVED'
                """).param("type", key).update();
        db.sql("UPDATE operating_settings SET version=version+1,updated_at=clock_timestamp() WHERE singleton").update();
        return new Settings(key, minimum, expectedVersion + 1);
    }

    public List<Publication> publications() {
        return db.sql("""
                SELECT id,snapshot_id,type,status,starts_at,ends_at FROM comparison_publication
                WHERE status IN ('ACTIVE','SCHEDULED') AND ends_at > now() ORDER BY starts_at,id
                """).query(Publication.class).list();
    }

    @Transactional
    public ComparisonBuild.Snapshot transition(UUID actor, UUID id, Change change) {
        var policy = lockMetricPolicy();
        var initial = build.get(id);
        var dataset = lock(initial.type());
        db.sql("SELECT id FROM comparison_snapshot WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).single();
        var snapshot = build.get(id);
        if (snapshot.version() != change.expectedVersion()) fail(HttpStatus.CONFLICT);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var approval = approval(id);
        switch (change.action()) {
            case APPROVE -> {
                if (snapshot.status() != ComparisonBuild.Status.IN_REVIEW) fail(HttpStatus.CONFLICT);
                var allowed = change.allowedMetrics();
                if (allowed == null || allowed.isEmpty() || allowed.stream().anyMatch(java.util.Objects::isNull) || !METRICS.containsAll(allowed)
                        || allowed.stream().distinct().count() != allowed.size()
                        || change.startsAt() == null || change.endsAt() == null
                        || !change.endsAt().isAfter(change.startsAt()) || !change.endsAt().isAfter(now)
                        || (change.reason() != null && change.reason().length() > 500)
                        || (actor.equals(approval.author()) && !validReason(change.reason()))) {
                    fail(HttpStatus.UNPROCESSABLE_ENTITY);
                }
                checkEligible(snapshot, dataset, allowed, policy);
                db.sql("""
                        UPDATE comparison_snapshot SET status = 'APPROVED', approved_by = :actor,
                            approved_metrics = ARRAY(SELECT jsonb_array_elements_text(CAST(:metrics AS jsonb))),
                            approved_start = :start, approved_end = :end, approval_reason = :reason
                        WHERE id = :id
                        """).param("actor", actor).param("id", id).param("metrics", json.writeValueAsString(allowed))
                        .param("start", change.startsAt()).param("end", change.endsAt())
                        .param("reason", change.reason(), java.sql.Types.VARCHAR).update();
            }
            case REJECT -> {
                if (snapshot.status() != ComparisonBuild.Status.IN_REVIEW) fail(HttpStatus.CONFLICT);
                if (!validReason(change.reason())) fail(HttpStatus.UNPROCESSABLE_ENTITY);
                db.sql("UPDATE comparison_snapshot SET status = 'READY', approval_reason = :reason WHERE id = :id")
                        .param("id", id).param("reason", change.reason()).update();
            }
            case PUBLISH, SCHEDULE -> {
                if (snapshot.status() != ComparisonBuild.Status.APPROVED || approval.approver() == null) fail(HttpStatus.CONFLICT);
                checkEligible(snapshot, dataset, approval.metrics(), policy);
                boolean scheduled = change.action() == Action.SCHEDULE;
                if (!approval.ends().isAfter(now) || scheduled != approval.starts().isAfter(now)) {
                    fail(HttpStatus.UNPROCESSABLE_ENTITY);
                }
                expire(snapshot.type());
                var overlap = db.sql("""
                        SELECT id FROM comparison_publication WHERE type = :type AND status IN ('ACTIVE','SCHEDULED')
                            AND (status = 'ACTIVE' AND :immediate OR (starts_at < :end AND ends_at > :start))
                        ORDER BY id
                        """).param("type", snapshot.type()).param("start", approval.starts())
                        .param("end", approval.ends()).param("immediate", !scheduled).query(UUID.class).list();
                if (!overlap.isEmpty()) {
                    if (scheduled || overlap.size() != 1 || !overlap.getFirst().equals(change.replacePublicationId())) {
                        fail(HttpStatus.CONFLICT);
                    }
                    unpublish(overlap.getFirst());
                } else if (change.replacePublicationId() != null) {
                    fail(HttpStatus.CONFLICT);
                }
                db.sql("""
                        INSERT INTO comparison_publication(id,type,snapshot_id,allowed_metrics,starts_at,ends_at,status,
                            approver_id,reason)
                        SELECT :publication,type,id,approved_metrics,approved_start,approved_end,:status,
                            approved_by,approval_reason FROM comparison_snapshot WHERE id = :id
                        """).param("publication", UUID.randomUUID()).param("id", id)
                        .param("status", scheduled ? "SCHEDULED" : "ACTIVE").update();
            }
            case UNPUBLISH -> {
                if (!validReason(change.reason())) fail(HttpStatus.UNPROCESSABLE_ENTITY);
                var count = db.sql("""
                        UPDATE comparison_publication SET status = 'UNPUBLISHED', version = version + 1
                        WHERE snapshot_id = :id AND status IN ('ACTIVE','SCHEDULED')
                        """).param("id", id).update();
                if (count == 0) fail(HttpStatus.CONFLICT);
            }
            default -> fail(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        db.sql("UPDATE comparison_snapshot SET version = version + 1 WHERE id = :id").param("id", id).update();
        return build.get(id);
    }

    @Transactional
    void promote(String type) {
        var policy = lockMetricPolicy();
        var dataset = lock(type);
        expire(type);
        var scheduled = db.sql("""
                SELECT id,snapshot_id,type,status,starts_at,ends_at FROM comparison_publication
                WHERE type = :type AND status = 'SCHEDULED' AND starts_at <= now() AND ends_at > now()
                ORDER BY starts_at,id LIMIT 1
                """).param("type", type).query(Publication.class).optional();
        if (scheduled.isEmpty()) return;
        var next = scheduled.get();
        var snapshot = build.get(next.snapshotId());
        var approval = approval(snapshot.id());
        String status = "ACTIVE";
        try {
            if (snapshot.status() != ComparisonBuild.Status.APPROVED) fail(HttpStatus.CONFLICT);
            checkEligible(snapshot, dataset, approval.metrics(), policy);
            if (db.sql("SELECT EXISTS(SELECT 1 FROM comparison_publication WHERE type = :type AND status = 'ACTIVE')")
                    .param("type", type).query(Boolean.class).single()) fail(HttpStatus.CONFLICT);
        } catch (ResponseStatusException invalid) {
            status = "INVALIDATED";
        }
        db.sql("UPDATE comparison_publication SET status = :status, version = version + 1 WHERE id = :id")
                .param("id", next.id()).param("status", status).update();
    }

    List<String> dueTypes() {
        return db.sql("""
                SELECT DISTINCT type FROM comparison_publication
                WHERE (status = 'SCHEDULED' AND starts_at <= now()) OR (status = 'ACTIVE' AND ends_at <= now())
                ORDER BY type
                """).query(String.class).list();
    }

    private List<String> lockMetricPolicy() {
        return db.sql("SELECT comparison_metrics FROM operating_settings WHERE singleton FOR SHARE")
                .query((row, n) -> List.of((String[]) row.getArray(1).getArray())).single();
    }

    private void checkEligible(ComparisonBuild.Snapshot snapshot, Dataset dataset, List<String> allowed, List<String> policy) {
        if (dataset.version() != snapshot.datasetVersion()) fail(HttpStatus.CONFLICT);
        if (allowed.isEmpty() || !policy.containsAll(allowed) || dataset.minSample() == null || snapshot.sampleSize() < dataset.minSample()
                || allowed.stream().anyMatch(key -> !hasSample(snapshot.metrics().path(key), dataset.minSample()))) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private boolean hasSample(JsonNode metric, int minimum) {
        if (metric.has("mean")) return metric.path("n").asInt() >= minimum;
        for (var area : metric) if (area.has("mean") && area.path("n").asInt() >= minimum) return true;
        return false;
    }

    private Approval approval(UUID id) {
        return db.sql("""
                SELECT created_by,approved_by,approved_metrics,approved_start,approved_end
                FROM comparison_snapshot WHERE id = :id
                """).param("id", id).query((row, index) -> {
            var metrics = row.getArray(3);
            return new Approval(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                    metrics == null ? List.of() : Arrays.asList((String[]) metrics.getArray()),
                    row.getObject(4, OffsetDateTime.class), row.getObject(5, OffsetDateTime.class));
        }).single();
    }

    private Dataset lock(String type) {
        return db.sql("SELECT version,min_sample,settings_version FROM comparison_dataset WHERE type = :type FOR UPDATE")
                .param("type", type).query(Dataset.class).single();
    }

    private void expire(String type) {
        db.sql("""
                UPDATE comparison_publication SET status = 'EXPIRED', version = version + 1
                WHERE type = :type AND status IN ('ACTIVE','SCHEDULED') AND ends_at <= now()
                """).param("type", type).update();
    }

    private void unpublish(UUID id) {
        db.sql("UPDATE comparison_publication SET status = 'UNPUBLISHED', version = version + 1 WHERE id = :id")
                .param("id", id).update();
    }

    private static boolean validReason(String reason) {
        if (reason == null) return false;
        var normalized = reason.strip();
        int length = normalized.codePointCount(0, normalized.length());
        return length >= 10 && length <= 500;
    }

    private static void fail(HttpStatus status) { throw new ResponseStatusException(status); }
}
