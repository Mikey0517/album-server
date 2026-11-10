CREATE TABLE IF NOT EXISTS patches (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  base_version_code INTEGER NOT NULL,
  patch_version     INTEGER NOT NULL,
  filename          TEXT NOT NULL,
  file_size         INTEGER NOT NULL,
  sha256            TEXT NOT NULL,
  release_notes     TEXT,
  is_active         INTEGER NOT NULL DEFAULT 1,
  created_at        TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_patches_base ON patches(base_version_code, patch_version DESC);
