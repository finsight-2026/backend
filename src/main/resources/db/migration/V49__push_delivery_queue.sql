CREATE TABLE app_push_delivery (
    notification_id uuid NOT NULL REFERENCES app_notification(id) ON DELETE CASCADE,
    session_id uuid NOT NULL,
    provider text NOT NULL CHECK (provider IN ('LOCAL','FCM')),
    state text NOT NULL DEFAULT 'WAITING' CHECK
        (state IN ('WAITING','SENDING','SIMULATED','ACCEPTED','CANCELED','EXPIRED','UNKNOWN','INVALID')),
    expires_at timestamptz NOT NULL,
    next_at timestamptz NOT NULL,
    claimed_at timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    PRIMARY KEY (notification_id,session_id)
);
CREATE INDEX app_push_delivery_ready ON app_push_delivery (provider,next_at)
    WHERE state='WAITING';
CREATE INDEX app_push_delivery_expiry ON app_push_delivery (expires_at) WHERE state='WAITING';
CREATE INDEX app_push_delivery_stale_claim ON app_push_delivery (claimed_at) WHERE state='SENDING';
