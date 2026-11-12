ALTER TABLE app_user ADD COLUMN last_login_at timestamptz;

ALTER TABLE app_user_consent DROP CONSTRAINT app_user_consent_pkey;
ALTER TABLE app_user_consent ADD COLUMN id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY;
CREATE INDEX app_user_consent_history_idx ON app_user_consent(user_id, recorded_at DESC, id DESC);

ALTER TABLE diagnosis ADD COLUMN profile_snapshot jsonb
    CHECK (jsonb_typeof(profile_snapshot) = 'object');
