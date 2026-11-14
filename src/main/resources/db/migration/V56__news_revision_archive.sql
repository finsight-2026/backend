ALTER TABLE news_revision DROP CONSTRAINT news_revision_status_check;
ALTER TABLE news_revision ADD CONSTRAINT news_revision_status_check
    CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','PUBLISHED','UNPUBLISHED','ARCHIVED'));
