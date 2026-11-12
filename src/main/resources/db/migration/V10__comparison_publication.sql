CREATE TABLE comparison_dataset (
    type text PRIMARY KEY,
    version bigint NOT NULL DEFAULT 0,
    min_sample integer CHECK (min_sample >= 30)
);
INSERT INTO comparison_dataset(type) VALUES
    ('DEBT'),('INCOME'),('EMERGENCY_FUND'),('FIXED_COST'),('FUTURE'),('FAMILY');

CREATE TABLE comparison_snapshot (
    id uuid PRIMARY KEY,
    type text NOT NULL REFERENCES comparison_dataset(type),
    period_start date NOT NULL,
    period_end date NOT NULL CHECK (period_end >= period_start),
    formula_version text NOT NULL,
    dataset_version bigint NOT NULL,
    sample_n integer NOT NULL CHECK (sample_n >= 0),
    metrics jsonb NOT NULL CHECK (jsonb_typeof(metrics) = 'object'),
    built_at timestamptz NOT NULL DEFAULT now(),
    status text NOT NULL CHECK (status IN ('BUILDING','READY','FAILED','IN_REVIEW','APPROVED','INVALIDATED')),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE(id,type)
);

CREATE TABLE comparison_snapshot_member (
    snapshot_id uuid NOT NULL REFERENCES comparison_snapshot(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    diagnosis_id uuid NOT NULL REFERENCES diagnosis(id) ON DELETE CASCADE,
    PRIMARY KEY(snapshot_id,user_id)
);
CREATE INDEX comparison_member_user_idx ON comparison_snapshot_member(user_id);

CREATE TABLE comparison_publication (
    id uuid PRIMARY KEY,
    type text NOT NULL,
    snapshot_id uuid NOT NULL,
    allowed_metrics text[] NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL CHECK (ends_at > starts_at),
    status text NOT NULL CHECK (status IN ('ACTIVE','SCHEDULED','UNPUBLISHED','EXPIRED','INVALIDATED')),
    approver_id uuid NOT NULL REFERENCES admin_account(id),
    reason text,
    version bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (snapshot_id,type) REFERENCES comparison_snapshot(id,type)
);
CREATE UNIQUE INDEX comparison_active_per_type ON comparison_publication(type) WHERE status = 'ACTIVE';
