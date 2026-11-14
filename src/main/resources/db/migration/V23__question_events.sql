CREATE TABLE diagnosis_question_event (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES survey_attempt(id) ON DELETE CASCADE,
    question_no smallint NOT NULL CHECK (question_no BETWEEN 1 AND 30),
    kind text NOT NULL CHECK (kind IN ('VIEWED','COMPLETED')),
    active_ms integer CHECK (active_ms BETWEEN 0 AND 86400000),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK ((kind='COMPLETED') = (active_ms IS NOT NULL))
);
CREATE INDEX diagnosis_question_event_attempt ON diagnosis_question_event(attempt_id, recorded_at DESC, id DESC);
