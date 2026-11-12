CREATE TABLE app_session (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    refresh_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL
);
CREATE INDEX app_session_user_idx ON app_session(user_id);

CREATE TABLE app_login_failure (
    email text PRIMARY KEY,
    failures integer NOT NULL CHECK (failures BETWEEN 1 AND 5),
    blocked_until timestamptz
);
CREATE TABLE app_login_request (
    source_ip text NOT NULL,
    requested_at timestamptz NOT NULL
);
CREATE INDEX app_login_request_lookup ON app_login_request(source_ip, requested_at);
