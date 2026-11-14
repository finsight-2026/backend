CREATE TABLE news_candidate (
    source_url text PRIMARY KEY,
    title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 80),
    article_published_at timestamptz NOT NULL,
    collected_at timestamptz NOT NULL
);
CREATE INDEX news_candidate_recent ON news_candidate(article_published_at DESC,source_url);

CREATE TABLE news_candidate_collection (
    collection_date date PRIMARY KEY,
    status text NOT NULL CHECK (status IN ('QUEUED','COMPLETED','FAILED')),
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 4),
    next_run_at timestamptz,
    completed_at timestamptz,
    accepted_count integer NOT NULL DEFAULT 0 CHECK (accepted_count>=0),
    error_code text
);
