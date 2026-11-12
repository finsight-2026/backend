ALTER TABLE app_data_export ADD COLUMN source_epoch bigint;
UPDATE app_data_export e SET source_epoch=u.content_event_epoch FROM app_user u WHERE u.id=e.user_id;
ALTER TABLE app_data_export ALTER COLUMN source_epoch SET NOT NULL;
ALTER TABLE app_data_export ADD COLUMN retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 3);
ALTER TABLE app_data_export ADD COLUMN next_run_at timestamptz;
CREATE INDEX app_data_export_pending_queue ON app_data_export(requested_at,id) WHERE status='PENDING';
