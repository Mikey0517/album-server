#!/usr/bin/env bash
set -euo pipefail
BACKUP_DIR="${BACKUP_DIR:-./backups}"
DATE=$(date +%Y%m%d_%H%M%S)
mkdir -p "$BACKUP_DIR"
docker compose stop album
cp data/app.db "$BACKUP_DIR/app_${DATE}.db"
docker compose start album
tar -czf "$BACKUP_DIR/media_${DATE}.tar.gz" -C ./data media
find "$BACKUP_DIR" -type f -mtime +7 -delete
echo "备份完成：$BACKUP_DIR/*_${DATE}.*"
