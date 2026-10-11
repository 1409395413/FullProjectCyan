#!/usr/bin/env bash
# =============================================================================
#  裸机 Ubuntu 恢复脚本
#  用法：  sudo bash /opt/portfolio/scripts/restore-ubuntu.sh backup/db-20261007-030000.sql.gz [backup/media-xxx.tar.gz]
#  ⚠️ 会覆盖现有数据，执行前请先停止后端：systemctl stop portfolio
# =============================================================================
set -euo pipefail

ENV_FILE=/opt/portfolio/deploy/portfolio.env
DB_FILE="${1:-}"
MEDIA_FILE="${2:-}"
UPLOAD_DIR=/var/lib/portfolio/upload

if [ -z "$DB_FILE" ] || [ ! -f "$DB_FILE" ]; then
  echo "用法: $0 <db-xxx.sql.gz> [media-xxx.tar.gz]" >&2
  exit 1
fi

if [ -f "$ENV_FILE" ]; then
  set -a; . "$ENV_FILE"; set +a
fi

MYSQL_DB="${MYSQL_DB:-Cyan}"

echo "[restore] 导入数据库：$DB_FILE"
gunzip -c "$DB_FILE" | mysql -h "${MYSQL_HOST:-127.0.0.1}" -P "${MYSQL_PORT:-3306}" \
  -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DB"

if [ -n "$MEDIA_FILE" ] && [ -f "$MEDIA_FILE" ]; then
  echo "[restore] 解压素材：$MEDIA_FILE"
  mkdir -p "$UPLOAD_DIR"
  tar xzf "$MEDIA_FILE" -C "$UPLOAD_DIR"
  chown -R portfolio:portfolio "$UPLOAD_DIR"
fi

echo "[restore] 完成。请执行 systemctl start portfolio"