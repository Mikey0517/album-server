-- 分享权限：viewer（只读，默认）/ editor（可写：上传、删照片）/ admin（管理员：可删相册）
ALTER TABLE album_shares ADD COLUMN role TEXT NOT NULL DEFAULT 'viewer';
