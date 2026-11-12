CREATE TABLE admin_pii_export (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES admin_account(id),
    scope text NOT NULL CHECK(scope IN ('PROFILE','ANSWERS')),
    status text NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','COMPLETED','FAILED','CANCELED')),
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    started_at timestamptz,
    completed_at timestamptz,
    expires_at timestamptz,
    error_code text
);
CREATE INDEX admin_pii_export_queue ON admin_pii_export(requested_at,id) WHERE status='QUEUED';

CREATE TABLE admin_pii_export_source (
    export_id uuid PRIMARY KEY REFERENCES admin_pii_export(id) ON DELETE CASCADE,
    grant_id uuid NOT NULL REFERENCES pii_grant(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    diagnosis_id uuid REFERENCES diagnosis(id) ON DELETE CASCADE,
    reason_encrypted bytea NOT NULL,
    archive_encrypted bytea
);
CREATE INDEX admin_pii_export_source_grant ON admin_pii_export_source(grant_id);
CREATE INDEX admin_pii_export_source_user ON admin_pii_export_source(user_id);
CREATE INDEX admin_pii_export_source_diagnosis ON admin_pii_export_source(diagnosis_id) WHERE diagnosis_id IS NOT NULL;

CREATE TABLE admin_pii_export_link (
    token_hash bytea PRIMARY KEY,
    export_id uuid NOT NULL REFERENCES admin_pii_export_source(export_id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX admin_pii_export_link_expiry ON admin_pii_export_link(expires_at);

CREATE FUNCTION mark_pii_export_source_removed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE admin_pii_export SET
        completed_at=CASE WHEN status='QUEUED' THEN clock_timestamp() ELSE completed_at END,
        error_code=coalesce(error_code,CASE WHEN status IN ('QUEUED','COMPLETED') THEN 'ACCESS_ENDED' END),
        status=CASE WHEN status='QUEUED' THEN 'FAILED' ELSE status END
    WHERE id=OLD.export_id;
    RETURN NULL;
END;
$$;
CREATE TRIGGER admin_pii_export_source_removed AFTER DELETE ON admin_pii_export_source
    FOR EACH ROW EXECUTE FUNCTION mark_pii_export_source_removed();

CREATE FUNCTION revoke_grant_export_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM admin_pii_export_source WHERE grant_id=NEW.id;
    RETURN NULL;
END;
$$;
CREATE TRIGGER admin_pii_export_grant_revoked AFTER UPDATE OF revoked_at ON pii_grant
    FOR EACH ROW WHEN (OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL)
    EXECUTE FUNCTION revoke_grant_export_source();

CREATE FUNCTION revoke_owner_pii_export_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner uuid;
BEGIN
    IF TG_TABLE_NAME='admin_role' THEN
        IF OLD.role<>'PRIVACY' THEN RETURN NULL; END IF;
        owner := OLD.admin_id;
    ELSE
        IF NEW.status='ACTIVE' OR OLD.status=NEW.status THEN RETURN NULL; END IF;
        owner := NEW.id;
    END IF;
    DELETE FROM admin_pii_export_source s USING admin_pii_export j WHERE s.export_id=j.id AND j.owner_id=owner;
    RETURN NULL;
END;
$$;
CREATE TRIGGER admin_pii_export_role_removed AFTER DELETE OR UPDATE OF role,admin_id ON admin_role
    FOR EACH ROW EXECUTE FUNCTION revoke_owner_pii_export_source();
CREATE TRIGGER admin_pii_export_owner_suspended AFTER UPDATE OF status ON admin_account
    FOR EACH ROW EXECUTE FUNCTION revoke_owner_pii_export_source();

ALTER VIEW admin_operation_monitor RENAME TO admin_operation_monitor_v32;
CREATE VIEW admin_operation_monitor AS
SELECT * FROM admin_operation_monitor_v32
UNION ALL
SELECT id,'PII_RAW',status,owner_id,scope,requested_at,started_at,completed_at,expires_at,NULL,
       CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END,1,
       coalesce(error_code,CASE WHEN expires_at<=now() THEN 'FILE_EXPIRED' END)
FROM admin_pii_export;

CREATE FUNCTION revoke_withdrawing_user_exports() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM admin_pii_export_source WHERE user_id=NEW.id;
    RETURN NULL;
END;
$$;
CREATE TRIGGER admin_pii_export_user_withdrawing AFTER UPDATE OF status ON app_user
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status AND NEW.status='WITHDRAWAL_PENDING')
    EXECUTE FUNCTION revoke_withdrawing_user_exports();
