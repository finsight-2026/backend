ALTER TABLE admin_account ADD COLUMN mfa_reauth_failures integer NOT NULL DEFAULT 0
    CHECK (mfa_reauth_failures BETWEEN 0 AND 4);

CREATE TABLE pii_grant (
    id uuid PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES admin_account(id) ON DELETE CASCADE,
    session_hash text NOT NULL REFERENCES admin_session(id_hash) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    scopes text[] NOT NULL,
    reason_encrypted bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX pii_grant_admin ON pii_grant(admin_id,expires_at);
CREATE INDEX pii_grant_user ON pii_grant(user_id);
CREATE INDEX pii_grant_session ON pii_grant(session_hash);
