ALTER TABLE media ADD COLUMN live_video_path TEXT;
ALTER TABLE media ADD COLUMN original_format TEXT;
ALTER TABLE media ADD COLUMN thumb_failed INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_media_live ON media(id) WHERE live_video_path IS NOT NULL;
