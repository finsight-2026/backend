ALTER TABLE app_session ADD COLUMN revocation_hash text;
CREATE UNIQUE INDEX app_session_revocation_hash
    ON app_session(revocation_hash) WHERE revocation_hash IS NOT NULL;
