CREATE TABLE screen_experiment (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK(char_length(name) BETWEEN 1 AND 80),
    screen_id text NOT NULL CHECK(screen_id='B_006'),
    variants text[] NOT NULL CHECK(cardinality(variants) BETWEEN 2 AND 4),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL CHECK(ends_at>starts_at),
    status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','PAUSED','ENDED')),
    version bigint NOT NULL DEFAULT 0,
    ended_at timestamptz,
    author_id uuid NOT NULL REFERENCES admin_account(id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(id,screen_id),
    CHECK ((status='ENDED')=(ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX screen_experiment_active ON screen_experiment(screen_id) WHERE status='ACTIVE';

CREATE TABLE screen_experiment_assignment (
    experiment_id uuid NOT NULL REFERENCES screen_experiment(id),
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    variant_id text NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(experiment_id,user_id),
    UNIQUE(experiment_id,user_id,variant_id)
);
CREATE INDEX screen_experiment_assignment_user ON screen_experiment_assignment(user_id);

CREATE TABLE screen_experiment_exposure (
    experiment_id uuid NOT NULL,
    user_id uuid NOT NULL,
    variant_id text NOT NULL,
    journey_id uuid NOT NULL,
    exposed_at timestamptz NOT NULL,
    PRIMARY KEY(experiment_id,user_id),
    FOREIGN KEY(experiment_id,user_id,variant_id)
        REFERENCES screen_experiment_assignment(experiment_id,user_id,variant_id) ON DELETE CASCADE,
    FOREIGN KEY(journey_id,user_id) REFERENCES diagnosis_journey(id,user_id) ON DELETE CASCADE
);
CREATE INDEX screen_experiment_exposure_journey ON screen_experiment_exposure(journey_id);

ALTER TABLE app_event DROP CONSTRAINT app_event_event_name_check;
ALTER TABLE app_event ADD CHECK (event_name IN ('USER_SIGNED_UP','PROFILE_CONSENT_COMPLETED','APP_FOREGROUND',
    'DIAGNOSIS_ENTRY_VIEWED','SURVEY_STARTED','QUESTION_VIEWED','QUESTION_COMPLETED','SURVEY_ABORTED','SURVEY_COMPLETED',
    'RESULT_VIEWED','GUIDE_VIEWED','GUIDE_COMPLETION_SET','NOTIFICATION_OPENED','CONTENT_IMPRESSION','CONTENT_VIEWED','EXPERIMENT_EXPOSED'));
ALTER TABLE app_event ADD COLUMN experiment_id uuid REFERENCES screen_experiment(id);
ALTER TABLE app_event ADD COLUMN experiment_screen_id text;
ALTER TABLE app_event ADD COLUMN experiment_variant_id text;
CREATE INDEX app_event_experiment ON app_event(experiment_id) WHERE experiment_id IS NOT NULL;
