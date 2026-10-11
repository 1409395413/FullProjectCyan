#!/bin/bash
# =============================================================================
#  Cyan · Studio —— 线上版本指纹一键核对脚本
#
#  用途：打印线上所有可核对组件的「版本号 / 指纹」，
#        与 docs/07-线上与仓库版本核对清单.md 里的「当前基线」表逐行比对。
#        一致 = 线上与 GitHub 仓库同步，无需逐项复查；
#        不一致 = 直接跳到该项对应的模块去确认，不用全量排查。
#
#  用法：ssh root@8.138.93.141 'bash -s' < scripts/verify-prod.sh
#        或在线上：bash /opt/portfolio/scripts/verify-prod.sh
#
#  说明：线上 /opt/portfolio 没有 .git，无法用 commit hash 直接比对，
#        因此改用「构建产物指纹」作为版本号 —— 每次重新构建，指纹必变。
# =============================================================================

WEB_DIR=/opt/portfolio/web
JAR=/opt/portfolio/app.jar
NGINX_CONF=/etc/nginx/conf.d/portfolio.conf
UPLOAD_DIR=/var/lib/portfolio/upload
ENV_FILE=/opt/portfolio/deploy/portfolio.env

# ---- 从环境文件读数据库连接参数，不在脚本里写死账号密码 ----
getv() {
  sed -n "s/^$1=//p" "$ENV_FILE" 2>/dev/null | head -1 | tr -d '\r' | sed -e 's/^"//' -e 's/"$//'
}
DB_USER=$(getv MYSQL_USER);     [ -z "$DB_USER" ] && DB_USER=root
DB_PASS=$(getv MYSQL_PASSWORD)
DB_NAME=$(getv MYSQL_DB);       [ -z "$DB_NAME" ] && DB_NAME=CyanWorks
DB_HOST=$(getv MYSQL_HOST);     [ -z "$DB_HOST" ] && DB_HOST=127.0.0.1
DB_PORT=$(getv MYSQL_PORT);     [ -z "$DB_PORT" ] && DB_PORT=3306

# 统一查询入口：加 </dev/null 防止密码为空时卡在交互输入
q() { mysql -u"$DB_USER" -p"$DB_PASS" -h"$DB_HOST" -P"$DB_PORT" "$DB_NAME" -N -e "$1" </dev/null 2>/dev/null | tr -d ' '; }

echo "==================== Cyan · Studio 线上版本指纹 ===================="
echo "核对时间: $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo "主机:     $(hostname)"
echo "--------------------------------------------------------------------"
printf '%-14s %-28s %s\n' "组件" "版本号 / 指纹" "说明"
echo "--------------------------------------------------------------------"

# ---- 1. 前端构建指纹：index.html 引用的主包哈希 ----
if [ -f "$WEB_DIR/index.html" ]; then
  MAIN=$(grep -oE 'index-[A-Za-z0-9_-]+\.js' "$WEB_DIR/index.html" | head -1)
  CSS=$(grep -oE 'index-[A-Za-z0-9_-]+\.css' "$WEB_DIR/index.html" | head -1)
  printf '%-14s %-28s %s\n' "前端构建" "${MAIN:-未找到}" "index.html 主 JS 包"
  [ -n "$CSS" ] && printf '%-14s %-28s %s\n' "前端样式" "$CSS" "index.html 主 CSS"
  printf '%-14s %-28s %s\n' "前端时间" "$(stat -c '%y' "$WEB_DIR/index.html" | cut -d. -f1)" "index.html 落盘时间"
else
  printf '%-14s %-28s %s\n' "前端构建" "缺失!" "$WEB_DIR/index.html 不存在"
fi

# ---- 2. 后端构建指纹：jar 的 sha256 前 12 位 + 构建时间 ----
if [ -f "$JAR" ]; then
  JH=$(sha256sum "$JAR" | cut -c1-12)
  JT=$(stat -c '%y' "$JAR" | cut -d. -f1)
  JS=$(du -h "$JAR" | cut -f1)
  printf '%-14s %-28s %s\n' "后端构建" "$JH" "app.jar sha256 前12位 ($JS)"
  printf '%-14s %-28s %s\n' "后端时间" "$JT" "app.jar 构建时间"
else
  printf '%-14s %-28s %s\n' "后端构建" "缺失!" "$JAR 不存在"
fi

# ---- 3. Nginx 配置指纹 ----
if [ -f "$NGINX_CONF" ]; then
  NH=$(md5sum "$NGINX_CONF" | cut -c1-8)
  printf '%-14s %-28s %s\n' "Nginx配置" "$NH" "portfolio.conf md5 前8位"
  # PATCH 是否在放行名单里（历史踩过的坑，单独列出）
  if grep -q 'PATCH' "$NGINX_CONF"; then
    printf '%-14s %-28s %s\n' "PATCH放行" "已放行" "工作对接改状态依赖 PATCH"
  else
    printf '%-14s %-28s %s\n' "PATCH放行" "!!! 未放行 !!!" "会导致后台 405"
  fi
else
  printf '%-14s %-28s %s\n' "Nginx配置" "缺失!" "$NGINX_CONF 不存在"
fi

# ---- 4. 站点内容版本（数据库里的内容，与代码无关）----
CV=$(q "SELECT version FROM site_content ORDER BY id DESC LIMIT 1;")
printf '%-14s %-28s %s\n' "内容版本" "${CV:-读取失败}" "site_content.version（改内容+1）"

# ---- 5. 素材统计 ----
MC=$(q "SELECT COUNT(*) FROM media_asset;")
US=$(du -sh "$UPLOAD_DIR" 2>/dev/null | cut -f1)
printf '%-14s %-28s %s\n' "素材数量" "${MC:-?} 个" "media_asset 条数 / 占用 ${US:-?}"

# ---- 6. 服务运行状态 ----
echo "--------------------------------------------------------------------"
printf '%-14s %s\n' "服务" "状态"
for s in portfolio nginx mysql redis-server; do
  ST=$(systemctl is-active "$s" 2>/dev/null)
  EN=$(systemctl is-enabled "$s" 2>/dev/null)
  printf '%-14s %s (开机自启: %s)\n' "$s" "$ST" "$EN"
done

# ---- 7. 运行时组件版本 ----
echo "--------------------------------------------------------------------"
printf '%-14s %s\n' "运行时" "版本"
printf '%-14s %s\n' "nginx"  "$(nginx -v 2>&1 | cut -d/ -f2)"
printf '%-14s %s\n' "mysql"  "$(mysqld --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
printf '%-14s %s\n' "redis"  "$(redis-server --version 2>/dev/null | grep -oE 'v=[0-9.]+' | cut -d= -f2)"
printf '%-14s %s\n' "java"   "$(java -version 2>&1 | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
printf '%-14s %s\n' "ffmpeg" "$(ffmpeg -version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1)"

# ---- 8. 公网连通性自检 ----
echo "--------------------------------------------------------------------"
printf '%-14s %s\n' "公网自检" "$(curl -s -o /dev/null -w 'HTTP %{http_code} / %{time_total}s' --max-time 10 http://127.0.0.1/)"
echo "===================================================================="
echo "比对方式：把上面的「版本号/指纹」逐行对照 docs/07 的当前基线表。"
echo "完全一致 = 线上与仓库同步；某一行不一致 = 只查那一项，不必全量复查。"
