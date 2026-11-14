ALTER TABLE app_user DROP CONSTRAINT app_user_status_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_status_check
    CHECK (status IN ('ACTIVE','WITHDRAWAL_PENDING','DATA_DELETION_PENDING'));

ALTER TABLE app_data_deletion ALTER COLUMN completed_at DROP NOT NULL;
ALTER TABLE app_data_deletion ALTER COLUMN completed_at DROP DEFAULT;
ALTER TABLE app_data_deletion ADD COLUMN operation_id uuid UNIQUE REFERENCES deletion_operation(id);
ALTER TABLE app_data_deletion ADD CHECK (completed_at IS NOT NULL OR operation_id IS NOT NULL);
CREATE UNIQUE INDEX app_data_deletion_pending_user ON app_data_deletion(user_id) WHERE completed_at IS NULL;
CREATE INDEX deletion_operation_data_queue ON deletion_operation(requested_at,id)
    WHERE kind='DATA_DELETION' AND status='SCHEDULED';
