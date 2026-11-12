CREATE TABLE app_password_reset (
    user_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL
);

CREATE TABLE app_password_reset_request (
    email_hash text NOT NULL,
    ip_hash text NOT NULL,
    requested_at timestamptz NOT NULL
);
CREATE INDEX app_password_reset_request_email ON app_password_reset_request(email_hash, requested_at);
CREATE INDEX app_password_reset_request_ip ON app_password_reset_request(ip_hash, requested_at);
CREATE INDEX app_password_reset_request_expiry ON app_password_reset_request(requested_at);
