CREATE TABLE admin_account (
    id uuid PRIMARY KEY,
    email text NOT NULL UNIQUE,
    name text NOT NULL,
    password_hash text NOT NULL,
    totp_secret_encrypted bytea NOT NULL,
    last_totp_step bigint NOT NULL DEFAULT -1,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    session_version bigint NOT NULL DEFAULT 0,
    mfa_blocked_until timestamptz
);

CREATE TABLE admin_role (
    admin_id uuid NOT NULL REFERENCES admin_account(id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('SUPER_ADMIN', 'OPERATOR', 'PRIVACY', 'ANALYST')),
    PRIMARY KEY (admin_id, role)
);

CREATE TABLE admin_auth_challenge (
    id uuid PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES admin_account(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    failed_count integer NOT NULL DEFAULT 0 CHECK (failed_count BETWEEN 0 AND 5),
    used_at timestamptz
);

CREATE TABLE admin_session (
    id_hash text PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES admin_account(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    last_interaction_at timestamptz NOT NULL,
    reauth_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    session_version bigint NOT NULL
);
CREATE INDEX admin_session_account_idx ON admin_session(admin_id);

CREATE TABLE admin_login_failure (
    email text PRIMARY KEY,
    failures integer NOT NULL CHECK (failures BETWEEN 1 AND 5),
    blocked_until timestamptz
);

CREATE TABLE admin_login_request (
    source_ip text NOT NULL,
    requested_at timestamptz NOT NULL
);
CREATE INDEX admin_login_request_lookup ON admin_login_request(source_ip, requested_at);
