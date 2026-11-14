CREATE TABLE signup_email_challenge (
    email text PRIMARY KEY,
    code_hash text NOT NULL,
    sent_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    used_at timestamptz
);

CREATE TABLE signup_email_delivery (
    email text NOT NULL,
    source_ip text NOT NULL,
    sent_at timestamptz NOT NULL
);
CREATE INDEX signup_delivery_email_idx ON signup_email_delivery(email, sent_at);
CREATE INDEX signup_delivery_ip_idx ON signup_email_delivery(source_ip, sent_at);
CREATE INDEX signup_delivery_expiry_idx ON signup_email_delivery(sent_at);

CREATE TABLE app_user_profile (
    user_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    attributes jsonb NOT NULL CHECK (jsonb_typeof(attributes) = 'object')
);

CREATE TABLE app_user_consent (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    document_kind text NOT NULL,
    document_version text NOT NULL,
    granted boolean NOT NULL,
    recorded_at timestamptz NOT NULL,
    method text NOT NULL,
    PRIMARY KEY (user_id, document_kind, document_version)
);
