ALTER TABLE comparison_snapshot ADD COLUMN approved_by uuid REFERENCES admin_account(id);
ALTER TABLE comparison_snapshot ADD COLUMN approved_metrics text[];
ALTER TABLE comparison_snapshot ADD COLUMN approved_start timestamptz;
ALTER TABLE comparison_snapshot ADD COLUMN approved_end timestamptz;
ALTER TABLE comparison_snapshot ADD COLUMN approval_reason text;
ALTER TABLE comparison_dataset ADD COLUMN settings_version bigint NOT NULL DEFAULT 0;
