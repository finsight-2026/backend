CREATE TABLE service_notice (
    id uuid PRIMARY KEY,
    published_revision_id uuid
);

CREATE TABLE service_notice_revision (
    id uuid PRIMARY KEY,
    notice_id uuid NOT NULL REFERENCES service_notice(id) ON DELETE CASCADE,
    revision_no integer NOT NULL,
    author_id uuid NOT NULL REFERENCES admin_account(id),
    status text NOT NULL CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','SCHEDULED','PUBLISHED','UNPUBLISHED','EXPIRED','ARCHIVED')),
    title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 80),
    summary text NOT NULL CHECK (char_length(summary) BETWEEN 1 AND 300),
    body text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 10000),
    sort_order integer NOT NULL CHECK (sort_order BETWEEN 0 AND 999),
    starts_at timestamptz,
    ends_at timestamptz,
    scheduled_by uuid REFERENCES admin_account(id),
    review_reason text,
    version bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (notice_id,revision_no),
    UNIQUE (notice_id,id),
    CHECK (ends_at IS NULL OR (starts_at IS NOT NULL AND starts_at<ends_at))
);
ALTER TABLE service_notice ADD FOREIGN KEY(id,published_revision_id)
    REFERENCES service_notice_revision(notice_id,id);
CREATE UNIQUE INDEX service_notice_one_schedule ON service_notice_revision(notice_id) WHERE status='SCHEDULED';
CREATE INDEX service_notice_due ON service_notice_revision(starts_at) WHERE status='SCHEDULED';

CREATE TABLE service_notice_publication_event (
    id uuid PRIMARY KEY,
    notice_id uuid NOT NULL REFERENCES service_notice(id) ON DELETE CASCADE,
    revision_id uuid NOT NULL REFERENCES service_notice_revision(id) ON DELETE CASCADE,
    event_type text NOT NULL CHECK (event_type IN ('PUBLISHED','UNPUBLISHED','EXPIRED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(revision_id,event_type)
);
