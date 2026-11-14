CREATE TABLE admin_dashboard_export (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES admin_account(id),
    date_from date NOT NULL,
    date_to date NOT NULL,
    age text,
    pressure_type text,
    format text NOT NULL CHECK (format IN ('CSV','PDF')),
    status text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','COMPLETED','FAILED','CANCELED')),
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    started_at timestamptz,
    completed_at timestamptz,
    expires_at timestamptz,
    error_code text,
    archive bytea
);
CREATE UNIQUE INDEX admin_dashboard_export_one_queued
    ON admin_dashboard_export(owner_id,date_from,date_to,age,pressure_type) NULLS NOT DISTINCT
    WHERE status='QUEUED';
CREATE INDEX admin_dashboard_export_queue ON admin_dashboard_export(requested_at,id) WHERE status='QUEUED';

CREATE TABLE admin_dashboard_export_link (
    token_hash bytea PRIMARY KEY,
    export_id uuid NOT NULL REFERENCES admin_dashboard_export(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX admin_dashboard_export_link_expiry ON admin_dashboard_export_link(expires_at);

CREATE TABLE admin_dashboard_export_epoch (singleton boolean PRIMARY KEY CHECK(singleton), version bigint NOT NULL);
INSERT INTO admin_dashboard_export_epoch VALUES (true,0);

CREATE FUNCTION invalidate_dashboard_exports() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND OLD.status=NEW.status THEN
        RETURN NEW;
    END IF;
    UPDATE admin_dashboard_export_epoch SET version=version+1;
    UPDATE admin_dashboard_export SET archive=NULL,error_code='SOURCE_CHANGED' WHERE archive IS NOT NULL;
    RETURN NEW;
END;
$$;
CREATE TRIGGER admin_dashboard_export_source_change
    AFTER INSERT OR UPDATE OF status ON deletion_operation
    FOR EACH ROW EXECUTE FUNCTION invalidate_dashboard_exports();

CREATE FUNCTION revoke_dashboard_export_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner uuid;
BEGIN
    IF TG_TABLE_NAME='admin_role' THEN
        IF OLD.role<>'ANALYST' THEN RETURN NULL; END IF;
        owner := OLD.admin_id;
    ELSE
        IF NEW.status='ACTIVE' OR OLD.status=NEW.status THEN RETURN NULL; END IF;
        owner := NEW.id;
    END IF;
    UPDATE admin_dashboard_export
    SET archive=NULL,error_code='ACCESS_REVOKED',
        completed_at=CASE WHEN status='QUEUED' THEN clock_timestamp() ELSE completed_at END,
        status=CASE WHEN status='QUEUED' THEN 'CANCELED' ELSE status END
    WHERE owner_id=owner AND (archive IS NOT NULL OR status='QUEUED');
    RETURN NULL;
END;
$$;
CREATE TRIGGER admin_dashboard_export_role_removed
    AFTER DELETE OR UPDATE OF role,admin_id ON admin_role
    FOR EACH ROW EXECUTE FUNCTION revoke_dashboard_export_owner();
CREATE TRIGGER admin_dashboard_export_owner_suspended
    AFTER UPDATE OF status ON admin_account
    FOR EACH ROW EXECUTE FUNCTION revoke_dashboard_export_owner();

ALTER VIEW admin_operation_monitor RENAME TO admin_operation_monitor_base;
CREATE VIEW admin_operation_monitor AS
SELECT * FROM admin_operation_monitor_base
UNION ALL
SELECT id,'DASHBOARD_AGGREGATE',status,owner_id,'DASHBOARD_FILTER',
       requested_at,started_at,completed_at,expires_at,NULL,
       CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END,1,
       coalesce(error_code,CASE WHEN expires_at<=now() THEN 'FILE_EXPIRED' END)
FROM admin_dashboard_export;
