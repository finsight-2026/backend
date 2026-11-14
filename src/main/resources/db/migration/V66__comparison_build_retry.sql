ALTER TABLE comparison_snapshot ADD COLUMN retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 3);
ALTER TABLE comparison_snapshot ADD COLUMN next_run_at timestamptz;
CREATE INDEX comparison_snapshot_build_queue ON comparison_snapshot(built_at,id) WHERE status='BUILDING';
