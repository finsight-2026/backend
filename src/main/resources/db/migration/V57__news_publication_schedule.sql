ALTER TABLE news_revision DROP CONSTRAINT news_revision_status_check;
ALTER TABLE news_revision ADD CONSTRAINT news_revision_status_check
    CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','SCHEDULED','PUBLISHED','UNPUBLISHED','EXPIRED','ARCHIVED'));
ALTER TABLE news_revision ADD COLUMN scheduled_by uuid REFERENCES admin_account(id);
CREATE UNIQUE INDEX news_revision_one_schedule ON news_revision(article_id) WHERE status='SCHEDULED';
CREATE INDEX news_revision_due_start ON news_revision(starts_at) WHERE status='SCHEDULED';
CREATE INDEX news_revision_due_end ON news_revision(ends_at) WHERE status='PUBLISHED' AND ends_at IS NOT NULL;
