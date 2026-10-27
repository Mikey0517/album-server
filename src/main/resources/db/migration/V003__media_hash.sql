ALTER TABLE media ADD COLUMN content_hash TEXT;
CREATE INDEX IF NOT EXISTS idx_media_hash ON media(content_hash);
