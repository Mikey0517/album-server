CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_posts_author ON posts(author_id);
CREATE INDEX IF NOT EXISTS idx_media_feed ON media(taken_at DESC, id DESC) WHERE post_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_posts_posted ON posts(posted_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_album_media_album ON album_media(album_id, sort_order, added_at);
CREATE INDEX IF NOT EXISTS idx_album_media_media ON album_media(media_id);
