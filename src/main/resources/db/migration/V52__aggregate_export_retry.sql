ALTER TABLE admin_dashboard_export
    ADD COLUMN retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count>=0),
    ADD COLUMN automatic_retry_count integer NOT NULL DEFAULT 0 CHECK (automatic_retry_count BETWEEN 0 AND 3),
    ADD COLUMN retryable boolean NOT NULL DEFAULT false,
    ADD COLUMN next_run_at timestamptz;
CREATE INDEX admin_dashboard_export_retry_ready ON admin_dashboard_export (next_run_at,requested_at,id)
    WHERE status='QUEUED';
