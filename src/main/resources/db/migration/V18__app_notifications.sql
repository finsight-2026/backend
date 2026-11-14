CREATE TABLE app_notification_preference (
    user_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT false,
    periodic boolean NOT NULL DEFAULT false,
    incomplete boolean NOT NULL DEFAULT false,
    guide boolean NOT NULL DEFAULT false,
    economy boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE app_notification (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('PERIODIC','INCOMPLETE','GUIDE','ECONOMY','EXPORT')),
    event_key text NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    read_at timestamptz,
    UNIQUE (user_id, kind, event_key)
);
CREATE INDEX app_notification_inbox ON app_notification(user_id, created_at DESC, id DESC);
CREATE INDEX app_notification_expiry ON app_notification(created_at);
