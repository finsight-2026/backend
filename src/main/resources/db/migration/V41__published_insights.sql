ALTER TABLE service_notice DROP CONSTRAINT service_notice_kind_check;
ALTER TABLE service_notice ADD CHECK(kind IN ('NOTICE','GUIDE','INSIGHT'));
ALTER TABLE service_notice_revision ADD COLUMN snapshot_id uuid REFERENCES comparison_snapshot(id);
ALTER TABLE service_notice_revision ADD COLUMN metric_key text;
ALTER TABLE service_notice_revision ADD COLUMN insight_explanation text;
ALTER TABLE service_notice_revision ADD CHECK(
    (snapshot_id IS NULL AND metric_key IS NULL AND insight_explanation IS NULL) OR
    (snapshot_id IS NOT NULL AND metric_key IS NOT NULL AND insight_explanation IS NOT NULL
     AND metric_key IN ('YFSI_MEAN','EXTERNAL_AREA_MEAN','RESOURCE_DOMAIN_MEAN','PERCEIVED_STRESS_MEAN')
     AND char_length(insight_explanation) BETWEEN 1 AND 1000));

CREATE FUNCTION public_comparison_mean(cell jsonb, minimum integer, sample integer)
RETURNS numeric LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT CASE WHEN jsonb_typeof(cell->'n')='number' AND (cell->>'n') ~ '^[0-9]+$'
        AND jsonb_typeof(cell->'mean')='number' THEN
        CASE WHEN (cell->>'n')::numeric BETWEEN minimum AND sample AND (cell->>'mean')::numeric BETWEEN 0 AND 100
            THEN (cell->>'mean')::numeric END
        END
$$;

CREATE FUNCTION public_comparison_metric(metrics jsonb, metric text, minimum integer, sample integer)
RETURNS jsonb LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT CASE metric
        WHEN 'YFSI_MEAN' THEN to_jsonb(public_comparison_mean(metrics->metric,minimum,sample))
        WHEN 'PERCEIVED_STRESS_MEAN' THEN to_jsonb(public_comparison_mean(metrics->metric,minimum,sample))
        WHEN 'EXTERNAL_AREA_MEAN' THEN (
            SELECT jsonb_object_agg(area,mean) FROM unnest(ARRAY['DEBT','INCOME','EMERGENCY_FUND','FIXED_COST','FUTURE','FAMILY']) area
            CROSS JOIN LATERAL (SELECT public_comparison_mean(metrics->metric->area,minimum,sample) AS mean) value WHERE mean IS NOT NULL
        )
        WHEN 'RESOURCE_DOMAIN_MEAN' THEN (
            SELECT jsonb_object_agg(area,mean) FROM unnest(ARRAY['FINANCIAL_LITERACY','MONEY_MANAGEMENT','SELF_EFFICACY']) area
            CROSS JOIN LATERAL (SELECT public_comparison_mean(metrics->metric->area,minimum,sample) AS mean) value WHERE mean IS NOT NULL
        )
    END
$$;

CREATE VIEW approved_comparison_metric AS
SELECT s.id AS snapshot_id,s.type,s.formula_version,s.period_start,s.period_end,s.sample_n,
       metric.key AS metric_key,value.metric_value
FROM comparison_snapshot s JOIN comparison_dataset d ON d.type=s.type CROSS JOIN operating_settings o
CROSS JOIN LATERAL unnest(s.approved_metrics) metric(key)
CROSS JOIN LATERAL (SELECT public_comparison_metric(s.metrics,metric.key,d.min_sample,s.sample_n) AS metric_value) value
WHERE s.status='APPROVED' AND s.sample_n>=d.min_sample AND metric.key=ANY(o.comparison_metrics)
    AND value.metric_value IS NOT NULL;

CREATE VIEW published_insight AS
SELECT n.id,r.id AS revision_id,r.revision_no,r.title,r.summary,r.body,r.insight_explanation,r.starts_at,
       m.type,m.formula_version,m.period_start,m.period_end,m.sample_n,m.metric_key,m.metric_value
FROM service_notice n JOIN service_notice_revision r ON r.id=n.published_revision_id
JOIN approved_comparison_metric m ON m.snapshot_id=r.snapshot_id AND m.metric_key=r.metric_key AND r.types=ARRAY[m.type]
JOIN comparison_publication p ON p.snapshot_id=m.snapshot_id AND m.metric_key=ANY(p.allowed_metrics)
CROSS JOIN operating_settings o
WHERE n.kind='INSIGHT' AND r.status='PUBLISHED' AND r.starts_at<=statement_timestamp()
    AND (r.ends_at IS NULL OR r.ends_at>statement_timestamp())
    AND p.status='ACTIVE' AND p.starts_at<=statement_timestamp() AND p.ends_at>statement_timestamp()
    AND p.allowed_metrics<@o.comparison_metrics;

CREATE TABLE insight_helpful (
    content_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    revision_id uuid NOT NULL,
    set_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(content_id,user_id),
    FOREIGN KEY(content_id,revision_id) REFERENCES service_notice_revision(notice_id,id)
);
CREATE INDEX insight_helpful_user ON insight_helpful(user_id);

ALTER TABLE app_event DROP CONSTRAINT app_event_event_name_check;
ALTER TABLE app_event ADD CHECK (event_name IN ('USER_SIGNED_UP','PROFILE_CONSENT_COMPLETED','APP_FOREGROUND',
    'DIAGNOSIS_ENTRY_VIEWED','SURVEY_STARTED','QUESTION_VIEWED','QUESTION_COMPLETED','SURVEY_ABORTED','SURVEY_COMPLETED',
    'RESULT_VIEWED','GUIDE_VIEWED','GUIDE_COMPLETION_SET','NOTIFICATION_OPENED','CONTENT_IMPRESSION','CONTENT_VIEWED',
    'EXPERIMENT_EXPOSED','HELPFUL_SET'));
ALTER TABLE app_event DROP CONSTRAINT app_event_content_kind_check;
ALTER TABLE app_event ADD CHECK(content_kind IN ('NEWS','GUIDE','INSIGHT'));
ALTER TABLE app_event DROP CONSTRAINT app_event_placement_check;
ALTER TABLE app_event ADD CHECK(placement IN ('NEWS_LIST','GUIDE_LIST','INSIGHT_LIST'));
