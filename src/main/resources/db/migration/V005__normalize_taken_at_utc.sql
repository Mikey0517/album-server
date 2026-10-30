-- 旧版本 taken_at 是无时区的 Asia/Shanghai 本地时间；统一转换为 UTC ISO8601，
-- 与新的拍摄时间提取逻辑以及 App 的本地时区展示保持一致。
UPDATE media
SET taken_at = strftime('%Y-%m-%dT%H:%M:%SZ', taken_at, '-8 hours')
WHERE taken_at NOT LIKE '%Z'
  AND taken_at NOT LIKE '%+%';
