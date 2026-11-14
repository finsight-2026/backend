CREATE TABLE operator_note (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    author_id uuid NOT NULL REFERENCES admin_account(id),
    text_encrypted bytea NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deleted_at timestamptz
);
CREATE INDEX operator_note_user ON operator_note(user_id, created_at DESC, id DESC);

CREATE TABLE note_history (
    note_id uuid NOT NULL REFERENCES operator_note(id) ON DELETE CASCADE,
    version bigint NOT NULL,
    text_encrypted bytea NOT NULL,
    actor_id uuid NOT NULL REFERENCES admin_account(id),
    archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (note_id, version)
);
