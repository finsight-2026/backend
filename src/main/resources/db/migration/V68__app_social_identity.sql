CREATE TABLE app_social_identity (
    provider text NOT NULL CHECK (provider IN ('NAVER', 'KAKAO', 'APPLE')),
    provider_subject text NOT NULL CHECK (length(provider_subject) BETWEEN 1 AND 255),
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    linked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (provider, provider_subject),
    UNIQUE (user_id, provider)
);

CREATE TABLE app_social_onboarding (
    token_hash text PRIMARY KEY,
    provider text NOT NULL CHECK (provider IN ('NAVER', 'KAKAO', 'APPLE')),
    provider_subject text NOT NULL CHECK (length(provider_subject) BETWEEN 1 AND 255),
    verified_email text,
    expires_at timestamptz NOT NULL
);

CREATE INDEX app_social_onboarding_expiry_idx ON app_social_onboarding(expires_at);

CREATE TABLE app_social_authorization (
    state_hash text PRIMARY KEY,
    verifier_hash text NOT NULL,
    provider text NOT NULL CHECK (provider IN ('NAVER', 'KAKAO', 'APPLE')),
    purpose text NOT NULL CHECK (purpose IN ('LOGIN', 'LINK', 'REAUTH', 'CANCEL_WITHDRAWAL')),
    source_ip text NOT NULL,
    user_id uuid REFERENCES app_user(id) ON DELETE CASCADE,
    session_id uuid REFERENCES app_session(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'WAITING'
        CHECK (status IN ('WAITING','EXCHANGING','AUTHORIZED','CANCELED','FAILED','USED')),
    provider_subject text,
    verified_email text,
    expires_at timestamptz NOT NULL,
    CHECK ((purpose IN ('LINK','REAUTH') AND user_id IS NOT NULL AND session_id IS NOT NULL)
        OR (purpose IN ('LOGIN','CANCEL_WITHDRAWAL') AND user_id IS NULL AND session_id IS NULL)),
    CHECK ((status = 'AUTHORIZED') = (provider_subject IS NOT NULL))
);

CREATE INDEX app_social_authorization_expiry_idx ON app_social_authorization(expires_at);
CREATE INDEX app_social_authorization_ip_idx ON app_social_authorization(source_ip, expires_at);
CREATE INDEX app_social_authorization_user_idx ON app_social_authorization(user_id);
CREATE INDEX app_social_authorization_session_idx ON app_social_authorization(session_id);
