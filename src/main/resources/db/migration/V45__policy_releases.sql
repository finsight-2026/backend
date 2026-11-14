CREATE TABLE policy_release (
    version text PRIMARY KEY CHECK (
        version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' AND version <> 'local-preview-v1'
    ),
    effective_date date NOT NULL UNIQUE,
    terms text NOT NULL CHECK (length(btrim(terms)) BETWEEN 1 AND 100000),
    privacy_policy text NOT NULL CHECK (length(btrim(privacy_policy)) BETWEEN 1 AND 100000),
    privacy_consent text NOT NULL CHECK (length(btrim(privacy_consent)) BETWEEN 1 AND 100000),
    research text NOT NULL CHECK (length(btrim(research)) BETWEEN 1 AND 100000)
);
