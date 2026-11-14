CREATE TABLE app_push_registration (
    session_id uuid PRIMARY KEY REFERENCES app_session(id) ON DELETE CASCADE,
    provider text NOT NULL CHECK (provider IN ('FCM', 'LOCAL')),
    registration_token text NOT NULL CHECK (length(registration_token) BETWEEN 20 AND 4096),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (provider, registration_token)
);
