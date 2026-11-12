ALTER TABLE deletion_operation DROP CONSTRAINT deletion_operation_status_check;
ALTER TABLE deletion_operation ADD CONSTRAINT deletion_operation_status_check
    CHECK (status IN ('SCHEDULED','COMPLETED','CANCELED','FAILED'));
ALTER TABLE deletion_operation ADD COLUMN retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 3);
ALTER TABLE deletion_operation ADD COLUMN next_run_at timestamptz;
ALTER TABLE deletion_operation ADD COLUMN error_code text;
CREATE INDEX app_user_withdrawal_request_idx ON app_user(withdrawal_request_id)
    WHERE status='WITHDRAWAL_PENDING';

CREATE OR REPLACE FUNCTION invalidate_dashboard_exports() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND (OLD.status=NEW.status OR NEW.status='FAILED') THEN
        RETURN NEW;
    END IF;
    UPDATE admin_dashboard_export_epoch SET version=version+1;
    UPDATE admin_dashboard_export SET archive=NULL,error_code='SOURCE_CHANGED' WHERE archive IS NOT NULL;
    RETURN NEW;
END;
$$;
