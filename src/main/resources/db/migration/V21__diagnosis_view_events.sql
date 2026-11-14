CREATE TABLE diagnosis_view_event (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES diagnosis(attempt_id) ON DELETE CASCADE,
    content_id text,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX diagnosis_view_result ON diagnosis_view_event(attempt_id, recorded_at) WHERE content_id IS NULL;
CREATE INDEX diagnosis_view_guide ON diagnosis_view_event(attempt_id, recorded_at) WHERE content_id IS NOT NULL;
