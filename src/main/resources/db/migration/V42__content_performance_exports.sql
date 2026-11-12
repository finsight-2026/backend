ALTER TABLE admin_dashboard_export ADD COLUMN kind text NOT NULL DEFAULT 'DASHBOARD_AGGREGATE'
    CHECK(kind IN ('DASHBOARD_AGGREGATE','CONTENT_PERFORMANCE'));
ALTER TABLE admin_dashboard_export ADD COLUMN content_kind text CHECK(content_kind IN ('NEWS','GUIDE','INSIGHT'));
ALTER TABLE admin_dashboard_export ADD COLUMN content_id text;
ALTER TABLE admin_dashboard_export ADD COLUMN content_revision integer CHECK(content_revision>0);
ALTER TABLE admin_dashboard_export ADD COLUMN combine_versions boolean NOT NULL DEFAULT false;
ALTER TABLE admin_dashboard_export ADD CHECK(NOT combine_versions OR content_revision IS NULL);
ALTER TABLE admin_dashboard_export ADD CHECK(
    (kind='DASHBOARD_AGGREGATE' AND content_kind IS NULL AND content_id IS NULL AND content_revision IS NULL AND NOT combine_versions)
    OR (kind='CONTENT_PERFORMANCE' AND age IS NULL));
DROP INDEX admin_dashboard_export_one_queued;
CREATE UNIQUE INDEX admin_dashboard_export_one_queued ON admin_dashboard_export
    (owner_id,kind,date_from,date_to,age,pressure_type,content_kind,content_id,content_revision,combine_versions)
    NULLS NOT DISTINCT WHERE status='QUEUED';

CREATE OR REPLACE VIEW admin_operation_monitor_v32 AS
SELECT * FROM admin_operation_monitor_base
UNION ALL
SELECT id,kind,status,owner_id,CASE kind WHEN 'CONTENT_PERFORMANCE' THEN 'CONTENT_FILTER' ELSE 'DASHBOARD_FILTER' END,
       requested_at,started_at,completed_at,expires_at,NULL,
       CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END,1,
       coalesce(error_code,CASE WHEN expires_at<=now() THEN 'FILE_EXPIRED' END)
FROM admin_dashboard_export;

CREATE INDEX app_event_helpful_period_end
    ON app_event(content_id,user_id,occurred_at DESC,received_at DESC,event_id DESC)
    INCLUDE(content_revision,content_type,completion_value) WHERE event_name='HELPFUL_SET';
