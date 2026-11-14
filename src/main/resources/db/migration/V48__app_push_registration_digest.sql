ALTER TABLE app_push_registration
    DROP CONSTRAINT app_push_registration_provider_registration_token_key;

CREATE UNIQUE INDEX app_push_registration_provider_digest_idx
    ON app_push_registration (provider, sha256(registration_token::bytea));
