CREATE TABLE admin_idempotency (
    actor_id uuid NOT NULL REFERENCES admin_account(id),
    route text NOT NULL,
    request_key text NOT NULL,
    request_hash text NOT NULL,
    response jsonb NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, route, request_key)
);
CREATE INDEX admin_idempotency_expiry_idx ON admin_idempotency(expires_at);

CREATE TABLE audit_log (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES admin_account(id),
    roles text[] NOT NULL,
    action text NOT NULL,
    target_type text NOT NULL,
    target_id text NOT NULL,
    result text NOT NULL,
    changes_safe jsonb NOT NULL,
    trace_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX audit_log_time_idx ON audit_log(created_at DESC,id DESC);
CREATE INDEX audit_log_actor_idx ON audit_log(actor_id,created_at DESC);
CREATE INDEX audit_log_target_idx ON audit_log(target_id,created_at DESC);
