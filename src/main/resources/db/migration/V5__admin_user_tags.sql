CREATE TABLE tag (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 30),
    name_normalized text NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE user_tag (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    tag_id uuid NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, tag_id)
);
CREATE INDEX user_tag_tag_idx ON user_tag(tag_id, user_id);
