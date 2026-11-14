CREATE TABLE app_notification_local_slot (
    notification_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    delivery_day date NOT NULL,
    category text NOT NULL CHECK (category IN ('REMINDER', 'ECONOMY', 'EXPORT'))
);
CREATE INDEX app_notification_local_slot_day ON app_notification_local_slot(user_id, delivery_day);
CREATE INDEX app_notification_local_slot_expiry ON app_notification_local_slot(delivery_day);

ALTER TABLE app_notification ADD COLUMN template_id text;
ALTER TABLE app_notification ADD COLUMN template_version text;

ALTER TABLE news_edition_item ADD COLUMN assigned_at timestamptz;
