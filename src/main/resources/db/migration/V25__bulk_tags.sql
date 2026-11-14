CREATE TABLE bulk_tag_preview (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES admin_account(id),
    action text NOT NULL CHECK (action IN ('ADD_TAGS','REMOVE_TAGS')),
    tag_ids jsonb NOT NULL,
    total bigint NOT NULL DEFAULT 0,
    excluded bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '10 minutes'
);
CREATE TABLE bulk_tag_target (
    preview_id uuid NOT NULL REFERENCES bulk_tag_preview(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    PRIMARY KEY (preview_id,user_id)
);
CREATE INDEX bulk_tag_target_user ON bulk_tag_target(user_id);
CREATE TABLE bulk_tag_job (
    id uuid PRIMARY KEY,
    preview_id uuid UNIQUE NOT NULL,
    actor_id uuid NOT NULL REFERENCES admin_account(id),
    status text NOT NULL CHECK (status IN ('QUEUED','COMPLETED','FAILED')),
    total bigint NOT NULL,
    excluded bigint NOT NULL,
    changed bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz
);
CREATE INDEX bulk_tag_job_queue ON bulk_tag_job(created_at,id) WHERE status='QUEUED';
