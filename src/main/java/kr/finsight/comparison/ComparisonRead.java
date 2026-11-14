package kr.finsight.comparison;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
class ComparisonRead {
    private final JdbcClient db;
    private final JsonMapper json;

    ComparisonRead(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    Map<String, Object> current(UUID user) {
        return db.sql("""
                WITH latest AS (
                    SELECT d.representative_type AS type, a.formula_version
                    FROM diagnosis d JOIN survey_attempt a ON a.id = d.attempt_id
                    JOIN app_user u ON u.id = a.user_id
                    WHERE u.id = :user AND u.status = 'ACTIVE'
                    ORDER BY d.completed_at DESC,d.id DESC LIMIT 1
                )
                SELECT p.status AS publication_status, s.status AS snapshot_status,
                       p.starts_at <= now() AS started, p.ends_at > now() AS unexpired,
                       c.min_sample,s.sample_n,s.period_start,s.period_end,s.formula_version,
                       (SELECT jsonb_object_agg(key,value) FROM unnest(p.allowed_metrics) key
                        CROSS JOIN LATERAL (SELECT public_comparison_metric(s.metrics,key,c.min_sample,s.sample_n) AS value) metric
                        WHERE value IS NOT NULL) AS public_metrics,
                       s.formula_version = latest.formula_version AS matching_formula,
                       p.allowed_metrics <@ policy.comparison_metrics AS allowed_by_policy
                FROM latest JOIN comparison_dataset c ON c.type = latest.type
                JOIN comparison_publication p ON p.type = latest.type AND p.status IN ('ACTIVE','INVALIDATED','EXPIRED')
                JOIN comparison_snapshot s ON s.id = p.snapshot_id
                CROSS JOIN operating_settings policy
                ORDER BY CASE p.status WHEN 'ACTIVE' THEN 0 ELSE 1 END, p.starts_at DESC,p.id DESC LIMIT 1
                """).param("user", user).query((row, index) -> {
            if ("INVALIDATED".equals(row.getString("publication_status")) ||
                    "INVALIDATED".equals(row.getString("snapshot_status"))) return unavailable("REBUILDING");
            if (!row.getBoolean("unexpired")) return unavailable("EXPIRED");
            var minimum = row.getObject("min_sample", Integer.class);
            int sample = row.getInt("sample_n");
            if (!"ACTIVE".equals(row.getString("publication_status")) || !"APPROVED".equals(row.getString("snapshot_status"))
                    || !row.getBoolean("started") || !row.getBoolean("matching_formula") || !row.getBoolean("allowed_by_policy")
                    || minimum == null || sample < minimum) return unavailable("NOT_PUBLISHED");

            var publicMetrics = row.getString("public_metrics");
            if (publicMetrics == null) return unavailable("NOT_PUBLISHED");
            var metrics = json.readTree(publicMetrics);
            return Map.<String, Object>of("available", true, "sampleSize", sample, "metrics", metrics,
                    "from", row.getObject("period_start", LocalDate.class), "to", row.getObject("period_end", LocalDate.class),
                    "formulaVersion", row.getString("formula_version"));
        }).optional().orElseGet(() -> unavailable("NOT_PUBLISHED"));
    }

    private static Map<String, Object> unavailable(String reason) {
        return Map.of("available", false, "reason", reason);
    }
}
