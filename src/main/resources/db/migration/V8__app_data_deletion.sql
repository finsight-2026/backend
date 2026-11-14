CREATE TABLE app_data_deletion (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (user_id, request_id)
);
