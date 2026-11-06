-- 私人相册 + 昵称唯一
--
-- visibility:
--   family  —— 家庭公共相册，所有登录用户可见（历史数据默认）
--   private —— 私人相册，仅创建者可见，可分享给指定用户
ALTER TABLE albums ADD COLUMN owner_id INTEGER REFERENCES users(id);
ALTER TABLE albums ADD COLUMN visibility TEXT NOT NULL DEFAULT 'family';

CREATE TABLE IF NOT EXISTS album_shares (
  album_id   INTEGER NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT DEFAULT (datetime('now')),
  PRIMARY KEY (album_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_album_shares_user ON album_shares(user_id);
CREATE INDEX IF NOT EXISTS idx_albums_owner ON albums(owner_id);

-- 昵称全局唯一（忽略大小写），注册和修改昵称时都会校验。
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_nickname ON users(nickname COLLATE NOCASE);
