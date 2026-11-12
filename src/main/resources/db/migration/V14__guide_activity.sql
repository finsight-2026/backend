CREATE TABLE guide_activity (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    content_id text NOT NULL,
    month_start date NOT NULL,
    content_version integer NOT NULL,
    representative_type text NOT NULL,
    title text NOT NULL,
    viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    PRIMARY KEY(user_id,content_id,month_start)
);
