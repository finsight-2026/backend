ALTER TABLE app_notification DROP CONSTRAINT app_notification_kind_check;
ALTER TABLE app_notification ADD CHECK (kind IN ('PERIODIC','INCOMPLETE','GUIDE','ECONOMY','EXPORT','NOTICE'));

ALTER TABLE campaign_job ADD COLUMN started_at timestamptz;
ALTER TABLE campaign_job ADD COLUMN completed_at timestamptz;
ALTER TABLE campaign_job ADD COLUMN error_code text;

CREATE TABLE campaign_local_delivery (
    id uuid PRIMARY KEY,
    campaign_id uuid NOT NULL REFERENCES campaign(id) ON DELETE CASCADE,
    notice_revision_id uuid NOT NULL REFERENCES service_notice_revision(id),
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status text NOT NULL CHECK (status IN ('WAITING','LOCAL_INBOX','EXCLUDED','CANCELED','FAILED')),
    reason text,
    notification_id uuid REFERENCES app_notification(id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(campaign_id,user_id)
);
CREATE UNIQUE INDEX campaign_notice_once ON campaign_local_delivery(user_id,notice_revision_id)
    WHERE status='LOCAL_INBOX';
CREATE INDEX campaign_delivery_waiting ON campaign_local_delivery(campaign_id,id) WHERE status='WAITING';
CREATE INDEX campaign_delivery_user ON campaign_local_delivery(user_id);
