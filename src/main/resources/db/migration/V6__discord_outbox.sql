CREATE TABLE discord_outbox (
    event_type text NOT NULL,
    event_id uuid NOT NULL,
    content text,
    state text NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING','SENDING','DELIVERED','FAILED','UNKNOWN')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_at timestamptz,
    PRIMARY KEY (event_type, event_id)
);
CREATE INDEX discord_outbox_pending_idx ON discord_outbox(next_attempt_at) WHERE state = 'PENDING';
