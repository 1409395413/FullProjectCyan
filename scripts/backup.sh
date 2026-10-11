#!/usr/bin/env bash
# =============================================================================
#  备份：站点内容（MySQL）+ 上传素材（upload-data 卷）
#  用法：  ./scripts/backup.sh            # 输出到 ./backup/
#         ./scripts/backup.sh /data/bak   # 指定目录
#
#  建议加定时任务（每天 03:00）：
#    0 3 * * * cd /opt/portfolio && ./scripts/backup.sh >> /var/log/pf-backup.log 2>&1
#
#  注意：这是"代码仓库之外的东西"。仓库里只有代码，所以备份一定不能只备份仓库。
# =============================================================================
set -euo pipefail

OUT_DIR="${1:-./backup}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"

COMPOSE="docker compose"
$COMPOSE version >/dev/null 2>&1 || COMPOSE="docker-compose"

echo "[backup] 目标目录: $OUT_DIR"

# ---------- 1. 数据库（含整站内容 JSON / 账号 / 审计 / 素材元数据）----------
$COMPOSE exec -T mysql mysqldump \
  --single-transaction --quick --default-character-set=utf8mb4 \
  -uroot -p"${MYSQL_ROOT_PASSWORD}" "${MYSQL_DB:-CyanWorks}" \
  | gzip > "$OUT_DIR/db-$STAMP.sql.gz"
echo "[backup] 数据库 -> db-$STAMP.sql.gz"

# ---------- 2. 上传素材（图片 / 视频）----------
TMP="$(mktemp -d)"
# 卷名用 compose 顶层的 name: portfolio 固定，不随目录名变化
VOL="${COMPOSE_PROJECT_NAME:-portfolio}_upload-data"
docker run --rm \
  -v "$VOL:/src:ro" \
  -v "$TMP:/out" \
  alpine sh -c "cd /src && tar czf /out/media.tar.gz ."
mv "$TMP/media.tar.gz" "$OUT_DIR/media-$STAMP.tar.gz"
rmdir "$TMP"
echo "[backup] 素材 ($VOL) -> media-$STAMP.tar.gz"

# ---------- 3. 清理：保留最近 14 份 ----------
ls -1t "$OUT_DIR"/db-*.sql.gz    | tail -n +15 | xargs -r rm -f
ls -1t "$OUT_DIR"/media-*.tar.gz | tail -n +15 | xargs -r rm -f

echo "[backup] 完成：$OUT_DIR/db-$STAMP.sql.gz  $OUT_DIR/media-$STAMP.tar.gz"
