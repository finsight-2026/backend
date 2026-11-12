CREATE TABLE media_asset (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES admin_account(id),
    mime text NOT NULL CHECK(mime IN ('image/jpeg','image/png')),
    width integer NOT NULL CHECK(width BETWEEN 1 AND 4096),
    height integer NOT NULL CHECK(height BETWEEN 1 AND 4096),
    status text NOT NULL CHECK(status='READY'),
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp()+interval '24 hours'),
    bytes bytea NOT NULL CHECK(octet_length(bytes)>0)
);
CREATE INDEX media_asset_expiry ON media_asset(expires_at);
ALTER TABLE news_revision ADD COLUMN media_id uuid REFERENCES media_asset(id);
CREATE INDEX news_revision_media ON news_revision(media_id) WHERE media_id IS NOT NULL;
