CREATE TABLE news_article (
    id uuid PRIMARY KEY,
    source_url text UNIQUE NOT NULL,
    published_revision integer
);
CREATE TABLE news_revision (
    article_id uuid NOT NULL REFERENCES news_article(id) ON DELETE CASCADE,
    revision integer NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    author_id uuid NOT NULL REFERENCES admin_account(id),
    status text NOT NULL CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','PUBLISHED','UNPUBLISHED')),
    title text NOT NULL,
    summary text NOT NULL,
    body text NOT NULL,
    reason text NOT NULL,
    action text NOT NULL,
    article_published_at timestamptz NOT NULL,
    types text[] NOT NULL,
    starts_at timestamptz,
    ends_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(article_id,revision)
);
ALTER TABLE news_article ADD FOREIGN KEY(id,published_revision) REFERENCES news_revision(article_id,revision);
CREATE TABLE news_edition_item (
    edition_date date NOT NULL,
    type text NOT NULL REFERENCES comparison_dataset(type),
    slot integer NOT NULL CHECK (slot BETWEEN 1 AND 3),
    article_id uuid NOT NULL REFERENCES news_article(id),
    PRIMARY KEY(edition_date,type,slot),
    UNIQUE(edition_date,type,article_id)
);
