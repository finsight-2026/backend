ALTER TABLE app_user ADD COLUMN comparison_test_account boolean NOT NULL DEFAULT false;
ALTER TABLE comparison_snapshot ADD COLUMN created_by uuid REFERENCES admin_account(id);
ALTER TABLE comparison_snapshot ADD COLUMN excluded_n integer NOT NULL DEFAULT 0;
ALTER TABLE comparison_snapshot ADD COLUMN failure_reason text;
