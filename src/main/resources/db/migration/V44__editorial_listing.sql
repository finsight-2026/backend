ALTER TABLE service_notice_revision ADD COLUMN updated_by uuid REFERENCES admin_account(id);
UPDATE service_notice_revision SET updated_by=author_id WHERE author_id IS NOT NULL;
ALTER TABLE news_revision ADD COLUMN updated_by uuid REFERENCES admin_account(id);
UPDATE news_revision SET updated_by=author_id;

CREATE INDEX service_notice_kind_listing ON service_notice(kind,id);
CREATE INDEX service_notice_revision_updated ON service_notice_revision(updated_at DESC,id DESC);
CREATE INDEX news_revision_updated ON news_revision(updated_at DESC,article_id DESC,revision DESC);
