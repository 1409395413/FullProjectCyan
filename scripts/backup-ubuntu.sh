#!/usr/bin/env bash
# =============================================================================
#  裸机 Ubuntu 备份脚本（仓库自带的 scripts/backup.sh 是 Docker 版，裸机不可用）
#
#  用法：  sudo bash /opt/portfolio/scripts/backup-ubuntu.sh [输出目录]
#
#  定时任务（每天凌晨 2:00，保留最近 2 份）：
#    0 2 * * * /usr/bin/flock -n /var/run/pf-backup.lock bash /opt/portfolio/scripts/backup-ubuntu.sh >> /var/log/pf-backup.log 2>&1
#
#  为什么用 flock：备份 256M 素材要跑一两分钟。若上一次还没跑完（比如服务器卡慢）
#  又叠一次，两份在前 simultaneously 写同名 tar.gz，会互相截断。
#  flock -n 拿不到锁就直接放弃本次，宁可少一次也不要备份半份。
#
#  轮转策略：KEEP=2 —— 只留最近 2 份。
#  每天一份，所以任何时刻磁盘上最多 2 份 DB + 2 份素材 ≈ 512 MB。
#  保留 2 份而不是更多：这份备份的定位是「误删/误改后的后悔药」，
#  不是异地灾备 —— 真出硬件故障，同机上的第 14 份也一起没了。
#  （异地方案评估过，站长按自身情况决定暂不上，此处不做。）
# =============================================================================
set -euo pipefail

ENV_FILE=/opt/portfolio/deploy/portfolio.env
OUT_DIR="${1:-/var/backups/portfolio}"
UPLOAD_DIR=/var/lib/portfolio/upload
KEEP=2

if [ -f "$ENV_FILE" ]; then
  set -a; . "$ENV_FILE"; set +a
fi

# 默认库名跟着 application.yml 与 nginx 的配置走：生产实际库是 CyanWorks
MYSQL_DB="${MYSQL_DB:-CyanWorks}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"

STAMP="$(date +%Y%m%d-%H%M%S)"
START="$(date +%s)"
mkdir -p "$OUT_DIR"

echo "=============================================================="
echo "[backup $(date '+%F %T')] 开始 —— 目标 $OUT_DIR（保留最近 $KEEP 份）"

# ---------- 1. 数据库（整站内容 JSON / 账号 / 审计 / 素材元数据 / 需求留言）----------
# --no-tablespaces：不加的话，应用用的普通账号没有 PROCESS 权限，
#   mysqldump 会甩一句 "Access denied ... PROCESS privilege" 的 Error。
#   MySQL 8 在 8.0.21+ 之后这个报错虽然不致命（dump 仍能写完），
#   但在严格环境下可能让整条命令返回非零，值得一行参数彻底消掉。
mysqldump -h "$MYSQL_HOST" -P "$MYSQL_PORT" \
  -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" \
  --single-transaction --quick --no-tablespaces \
  --default-character-set=utf8mb4 "$MYSQL_DB" \
  | gzip > "$OUT_DIR/db-$STAMP.sql.gz"

DB_SIZE=$(stat -c %s "$OUT_DIR/db-$STAMP.sql.gz")
if [ "$DB_SIZE" -lt 1024 ]; then
  echo "[backup] !! 数据库备份异常小（${DB_SIZE} 字节），很可能是导出失败"
  exit 1
fi
echo "[backup] 数据库 $MYSQL_DB -> db-$STAMP.sql.gz ($(( DB_SIZE / 1024 )) KiB)"

# ---------- 2. 上传素材（图片 / 视频）----------
if [ -d "$UPLOAD_DIR" ] && [ -n "$(ls -A "$UPLOAD_DIR" 2>/dev/null)" ]; then
  tar czf "$OUT_DIR/media-$STAMP.tar.gz" -C "$UPLOAD_DIR" . || {
    echo "[backup] !! 素材打包失败"; exit 1
  }
  MD_SIZE=$(stat -c %s "$OUT_DIR/media-$STAMP.tar.gz")
  echo "[backup] 素材 $UPLOAD_DIR -> media-$STAMP.tar.gz ($(( MD_SIZE / 1024 / 1024 )) MiB)"
else
  echo "[backup] 素材目录为空或不存在，跳过"
fi

# ---------- 3. 清理：只保留最近 KEEP 份 ----------
# ls -1t 按修改时间倒序；tail -n +$((KEEP+1)) 从第 KEEP+1 行开始，即所有更老的
ls -1t "$OUT_DIR"/db-*.sql.gz    2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
ls -1t "$OUT_DIR"/media-*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

# ---------- 4. 收尾：汇报磁盘占用，方便一眼看出有没有失控 ----------
echo "[backup] 当前保留份数：DB $(ls -1 "$OUT_DIR"/db-*.sql.gz 2>/dev/null | wc -l) / 素材 $(ls -1 "$OUT_DIR"/media-*.tar.gz 2>/dev/null | wc -l)"
echo "[backup] 备份目录占用：$(du -sh "$OUT_DIR" 2>/dev/null | cut -f1)，磁盘剩余：$(df -h / | awk 'NR==2{print $4}')"
echo "[backup] 耗时 $(( $(date +%s) - START )) 秒，完成。"
