ALTER TABLE news_edition_item ADD COLUMN article_revision integer;

UPDATE news_edition_item e SET article_revision=coalesce(a.published_revision,(
    SELECT r.revision FROM news_revision r WHERE r.article_id=e.article_id AND r.status='SCHEDULED'
        AND e.type=ANY(r.types) AND r.starts_at<(e.edition_date+1)::timestamp AT TIME ZONE 'Asia/Seoul'
        AND (r.ends_at IS NULL OR r.ends_at>e.edition_date::timestamp AT TIME ZONE 'Asia/Seoul')
))
FROM news_article a WHERE a.id=e.article_id;

ALTER TABLE news_edition_item ADD FOREIGN KEY(article_id,article_revision)
    REFERENCES news_revision(article_id,revision);
ALTER TABLE news_edition_item ADD CONSTRAINT news_edition_item_revision_required
    CHECK (article_revision IS NOT NULL) NOT VALID;

CREATE TABLE news_edition_version (
    edition_date date NOT NULL,
    type text NOT NULL REFERENCES comparison_dataset(type),
    version bigint NOT NULL CHECK (version>0),
    PRIMARY KEY(edition_date,type)
);
