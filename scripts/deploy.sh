#!/usr/bin/env bash
set -euo pipefail

# Linux 宿主机上 bind mount 的 ./data 由容器内 uid=1000 写入。
mkdir -p data/media/thumbs backups
if [ "$(id -u)" -ne 0 ]; then
  if [ ! -w data ]; then
    echo "data/ 当前用户不可写，请执行：sudo chown -R 1000:1000 data" >&2
    exit 1
  fi
else
  chown -R 1000:1000 data
fi

docker compose up -d --build

echo "等待服务就绪..."
for i in $(seq 1 40); do
  if curl -fsS http://127.0.0.1:8000/health >/dev/null 2>&1; then
    echo "服务已就绪：http://127.0.0.1:8000/health"
    curl -fsS http://127.0.0.1:8000/health
    echo
    exit 0
  fi
  sleep 2
done

echo "服务启动超时，请查看：docker compose logs --tail=200 album" >&2
exit 1
