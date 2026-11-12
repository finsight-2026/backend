ALTER TABLE app_session ADD COLUMN reauthenticated_at timestamptz;
ALTER TABLE app_user ADD COLUMN withdrawal_due_at timestamptz;
ALTER TABLE app_user ADD COLUMN withdrawal_request_id uuid;
CREATE INDEX app_user_withdrawal_due_idx ON app_user(withdrawal_due_at)
    WHERE status = 'WITHDRAWAL_PENDING';

CREATE TABLE withdrawal_total (
    singleton boolean PRIMARY KEY CHECK (singleton),
    completed_count bigint NOT NULL DEFAULT 0
);
INSERT INTO withdrawal_total(singleton) VALUES (true);
