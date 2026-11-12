CREATE TABLE admin_password_reset (
    admin_id uuid PRIMARY KEY REFERENCES admin_account(id) ON DELETE CASCADE,
    token_hash text NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    delivery_token_encrypted bytea,
    next_delivery_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX admin_password_reset_delivery ON admin_password_reset(next_delivery_at,admin_id)
    WHERE delivery_token_encrypted IS NOT NULL;
CREATE INDEX admin_password_reset_expiry ON admin_password_reset(expires_at);

CREATE TABLE admin_password_reset_request (
    email_hash text NOT NULL,
    ip_hash text NOT NULL,
    requested_at timestamptz NOT NULL
);
CREATE INDEX admin_reset_request_email ON admin_password_reset_request(email_hash,requested_at);
CREATE INDEX admin_reset_request_ip ON admin_password_reset_request(ip_hash,requested_at);
CREATE INDEX admin_reset_request_expiry ON admin_password_reset_request(requested_at);
