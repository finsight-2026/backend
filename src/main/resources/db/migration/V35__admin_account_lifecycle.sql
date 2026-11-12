ALTER TABLE admin_account DROP CONSTRAINT admin_account_status_check;
ALTER TABLE admin_account ADD CHECK(status IN ('INVITED','ACTIVE','SUSPENDED'));
ALTER TABLE admin_account ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE admin_account ALTER COLUMN totp_secret_encrypted DROP NOT NULL;
ALTER TABLE admin_account ADD CHECK(status<>'ACTIVE' OR (password_hash IS NOT NULL AND totp_secret_encrypted IS NOT NULL));
ALTER TABLE admin_account ADD COLUMN version bigint NOT NULL DEFAULT 0;

CREATE TABLE admin_invitation (
    token_hash text PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES admin_account(id) ON DELETE CASCADE,
    purpose text NOT NULL CHECK(purpose IN ('INVITATION','MFA_RESET')),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    enrollment_id uuid,
    enrollment_secret bytea,
    enrollment_expires_at timestamptz,
    failed_count integer NOT NULL DEFAULT 0 CHECK(failed_count BETWEEN 0 AND 5),
    blocked_until timestamptz
);
CREATE UNIQUE INDEX admin_invitation_one_open ON admin_invitation(admin_id) WHERE used_at IS NULL AND revoked_at IS NULL;
CREATE INDEX admin_invitation_expiry ON admin_invitation(expires_at);

CREATE TABLE admin_recovery_code (
    admin_id uuid NOT NULL REFERENCES admin_account(id) ON DELETE CASCADE,
    code_hash text NOT NULL,
    used_at timestamptz,
    PRIMARY KEY(admin_id,code_hash)
);
