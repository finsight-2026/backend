ALTER TABLE deletion_operation ADD COLUMN requeue_count integer NOT NULL DEFAULT 0 CHECK (requeue_count>=0);
