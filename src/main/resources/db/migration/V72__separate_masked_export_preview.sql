CREATE TABLE admin_masked_export_preview (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES admin_account(id) ON DELETE CASCADE,
    total integer NOT NULL DEFAULT 0,
    eligible integer NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '10 minutes'),
    CHECK (eligible BETWEEN 0 AND total)
);
CREATE INDEX admin_masked_export_preview_expiry ON admin_masked_export_preview(expires_at);

CREATE TABLE admin_masked_export_preview_user (
    preview_id uuid NOT NULL REFERENCES admin_masked_export_preview(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    data_epoch bigint NOT NULL,
    PRIMARY KEY (preview_id, user_id)
);
CREATE INDEX admin_masked_export_preview_user_lookup ON admin_masked_export_preview_user(user_id);

DELETE FROM campaign_target_preview WHERE purpose='MASKED_EXPORT';
