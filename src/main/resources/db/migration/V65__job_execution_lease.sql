CREATE TABLE job_execution_lease (
    queue text NOT NULL,
    job_id uuid NOT NULL,
    owner_token uuid NOT NULL,
    claimed_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY(queue,job_id)
);
CREATE INDEX job_execution_lease_expiry ON job_execution_lease(expires_at);
