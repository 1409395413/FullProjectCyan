#!/usr/bin/env bash
# =============================================================================
#  恢复：把 backup.sh 产出的两份文件还原回去
#  用法：  ./scripts/restore.sh backup/db-20261007-030000.sql.gz backup/media-20261007-030000.tar.gz
#
#  ⚠️ 会覆盖现有数据。执行前请确认容器内站点已停止写入（后台没在编辑）。
# =============================================================================
set -euo pipefail

DB_FILE="${1:-}"
MEDIA_FILE="${2:-}"

if [ -z "$DB_FILE" ] || [ ! -f "$DB_FILE" ]; then
  echo "用法: $0 <db-xxx.sql.gz> [media-xxx.tar.gz]" >&2
  exit 1
fi

COMPOSE="docker compose"
$COMPOSE version >/dev/null 2>&1 || COMPOSE="docker-compose"

echo "[restore] 导入数据库: $DB_FILE"
gunzip -c "$DB_FILE" | $COMPOSE exec -T mysql mysql \
  -uroot -p"${MYSQL_ROOT_PASSWORD}" "${MYSQL_DB:-CyanWorks}"

if [ -n "$MEDIA_FILE" ] && [ -f "$MEDIA_FILE" ]; then
  echo "[restore] 还原素材: $MEDIA_FILE"
  VOL="${COMPOSE_PROJECT_NAME:-portfolio}_upload-data"
  docker run --rm -v "$VOL:/dst" -v "$(dirname "$(realpath "$MEDIA_FILE")"):/src:ro" \
    alpine sh -c "cd /dst && tar xzf /src/$(basename "$MEDIA_FILE")"
fi

# 数据库已换，清掉 Redis 里的旧缓存
$COMPOSE exec -T redis redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning FLUSHDB >/dev/null 2>&1 || true

$COMPOSE restart api
echo "[restore] 完成"
