CREATE TABLE campaign (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES admin_account(id),
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
    segment_id uuid REFERENCES segment(id),
    segment_version integer,
    notice_revision_id uuid NOT NULL REFERENCES service_notice_revision(id),
    template_id text NOT NULL CHECK (template_id='NTF-06'),
    template_version text NOT NULL,
    title_snapshot text NOT NULL,
    body_snapshot text NOT NULL,
    destination text NOT NULL CHECK (destination='B_003'),
    requested_at timestamptz,
    scheduled_at timestamptz,
    status text NOT NULL CHECK (status IN ('DRAFT','SCHEDULED','QUEUED','SENDING','COMPLETED','PARTIAL_FAILED','FAILED','CANCELED','CANCEL_REQUESTED')),
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (segment_id,segment_version) REFERENCES segment_revision(segment_id,revision_no) MATCH FULL
);

CREATE TABLE campaign_target (
    campaign_id uuid NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    PRIMARY KEY (campaign_id,user_id)
);
CREATE INDEX campaign_target_user ON campaign_target(user_id);

CREATE TABLE campaign_preview (
    id uuid PRIMARY KEY,
    campaign_id uuid NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
    actor_id uuid NOT NULL REFERENCES admin_account(id),
    version bigint NOT NULL,
    execution_at timestamptz NOT NULL,
    counts jsonb NOT NULL,
    expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '10 minutes',
    used_at timestamptz
);
CREATE INDEX campaign_preview_campaign ON campaign_preview(campaign_id);
CREATE INDEX campaign_preview_expiry ON campaign_preview(expires_at);

CREATE TABLE campaign_job (
    id uuid PRIMARY KEY,
    campaign_id uuid UNIQUE NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
    preview_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
