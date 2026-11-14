CREATE TABLE segment (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 50),
    description text NOT NULL CHECK (char_length(description) <= 300),
    status text NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    current_version integer NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE segment_revision (
    segment_id uuid NOT NULL REFERENCES segment(id) ON DELETE CASCADE,
    revision_no integer NOT NULL CHECK (revision_no >= 1),
    conditions jsonb NOT NULL,
    created_by uuid NOT NULL REFERENCES admin_account(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (segment_id,revision_no)
);
ALTER TABLE segment ADD FOREIGN KEY (id,current_version)
    REFERENCES segment_revision(segment_id,revision_no) DEFERRABLE INITIALLY DEFERRED;
