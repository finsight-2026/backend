ALTER TABLE admin_dashboard_export DROP CONSTRAINT admin_dashboard_export_kind_check;
ALTER TABLE admin_dashboard_export DROP CONSTRAINT admin_dashboard_export_check1;
ALTER TABLE admin_dashboard_export ADD COLUMN user_selection_hash text;
ALTER TABLE admin_dashboard_export ADD COLUMN user_selection_count integer CHECK(user_selection_count > 0);
ALTER TABLE admin_dashboard_export ADD CHECK (
    (kind='DASHBOARD_AGGREGATE' AND content_kind IS NULL AND content_id IS NULL AND content_revision IS NULL AND NOT combine_versions AND user_selection_hash IS NULL)
    OR (kind='CONTENT_PERFORMANCE' AND age IS NULL AND user_selection_hash IS NULL)
    OR (kind='MASKED_USERS' AND format='CSV' AND user_selection_hash IS NOT NULL AND content_id IS NOT NULL
        AND content_id=user_selection_hash AND age IS NULL AND pressure_type IS NULL
        AND content_kind IS NULL AND content_revision IS NULL AND NOT combine_versions));
-- Keep the existing queue index usable by the previous application during rollback.
CREATE UNIQUE INDEX admin_masked_export_one_queued ON admin_dashboard_export(owner_id,user_selection_hash)
    WHERE status='QUEUED' AND kind='MASKED_USERS';

CREATE TABLE admin_masked_export_user (
    export_id uuid NOT NULL REFERENCES admin_dashboard_export(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    data_epoch bigint NOT NULL,
    PRIMARY KEY(export_id,user_id)
);
CREATE INDEX admin_masked_export_user_lookup ON admin_masked_export_user(user_id);

CREATE OR REPLACE VIEW admin_operation_monitor_v32 AS
SELECT * FROM admin_operation_monitor_base
UNION ALL
SELECT id,kind,status,owner_id,CASE kind WHEN 'CONTENT_PERFORMANCE' THEN 'CONTENT_FILTER'
    WHEN 'MASKED_USERS' THEN 'MASKED_USERS' ELSE 'DASHBOARD_FILTER' END,
       requested_at,started_at,completed_at,expires_at,NULL,
       CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END,1,
       coalesce(error_code,CASE WHEN expires_at<=now() THEN 'FILE_EXPIRED' END)
FROM admin_dashboard_export;

CREATE OR REPLACE FUNCTION revoke_dashboard_export_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner uuid;
DECLARE removed_role text;
BEGIN
    IF TG_TABLE_NAME='admin_role' THEN
        IF OLD.role NOT IN ('ANALYST','OPERATOR') THEN RETURN NULL; END IF;
        owner := OLD.admin_id;
        removed_role := OLD.role;
    ELSE
        IF NEW.status='ACTIVE' OR OLD.status=NEW.status THEN RETURN NULL; END IF;
        owner := NEW.id;
    END IF;
    UPDATE admin_dashboard_export
    SET archive=NULL,error_code='ACCESS_REVOKED',
        completed_at=CASE WHEN status='QUEUED' THEN clock_timestamp() ELSE completed_at END,
        status=CASE WHEN status='QUEUED' THEN 'CANCELED' ELSE status END
    WHERE owner_id=owner AND (archive IS NOT NULL OR status='QUEUED')
        AND (removed_role IS NULL OR (kind='MASKED_USERS')=(removed_role='OPERATOR'));
    RETURN NULL;
END;
$$;
