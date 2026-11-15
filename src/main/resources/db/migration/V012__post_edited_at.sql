-- 已发布动态允许作者修改文案：记录最后一次编辑时间，NULL 表示未编辑。
ALTER TABLE posts ADD COLUMN edited_at TEXT;
