CREATE TABLE diagnosis_journey (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    entered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (id, user_id)
);
CREATE INDEX diagnosis_journey_entered ON diagnosis_journey(entered_at);
CREATE INDEX diagnosis_journey_user ON diagnosis_journey(user_id);
ALTER TABLE survey_attempt ADD COLUMN journey_id uuid UNIQUE;
ALTER TABLE survey_attempt ADD FOREIGN KEY (journey_id,user_id)
    REFERENCES diagnosis_journey(id,user_id) ON DELETE CASCADE;

CREATE TABLE diagnosis_milestone (
    attempt_id uuid NOT NULL REFERENCES survey_attempt(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('Q1_VIEWED','Q15_COMPLETED')),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (attempt_id,kind)
);
