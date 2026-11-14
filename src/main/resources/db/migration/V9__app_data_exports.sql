CREATE TABLE app_data_export (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status text NOT NULL CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz,
    archive bytea
);
CREATE UNIQUE INDEX app_data_export_one_pending ON app_data_export(user_id) WHERE status = 'PENDING';
CREATE INDEX app_data_export_owner_idx ON app_data_export(user_id, requested_at DESC);
