# =============================================================================
#  换设备 / 换服务器：一键导出「素材 + 数据库」
#
#  用法：powershell -ExecutionPolicy Bypass -File .\toolchain\export-for-migration.ps1
#
#  产出：仓库根目录下 portfolio-migration-<日期>.tar.gz
#        内含  media/   —— 全部上传素材（保持 2026/10/xxx.mp4 这类相对结构）
#               cyan.sql —— 全库 dump（含 site_content / media_asset / admin_user）
#
#  在新机器 / 新服务器上恢复：
#    1) 解压：tar -xzf portfolio-migration-*.tar.gz -C /tmp
#    2) 素材：mkdir -p /var/lib/portfolio/upload
#             cp -r /tmp/media/. /var/lib/portfolio/upload/
#             chown -R portfolio:portfolio /var/lib/portfolio/upload
#    3) 数据：mysql -uroot -p CyanWorks < /tmp/cyan.sql
#    4) 确认 portfolio.env 里 APP_UPLOAD_DIR=/var/lib/portfolio/upload（绝对路径）
#    5) systemctl restart portfolio
#
#  设计原则：素材放项目目录之外 + 绝对路径，所以换个目录 clone 项目、
#            重新打包部署，都不会导致 /media/** 变 404。
# =============================================================================
$ErrorActionPreference = 'Stop'
$Repo   = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Tool   = Join-Path $env:USERPROFILE '.toolchain'

# ---- 读 .env ----
$envFile = Join-Path $Repo '.env'
$cfg = @{}
if (Test-Path $envFile) {
  foreach ($line in Get-Content $envFile) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $i = $t.IndexOf('=')
    if ($i -lt 1) { continue }
    $cfg[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim().Trim('"', "'")
  }
  Write-Host "[export] 已读取 $envFile"
} else {
  Write-Host "[export] 未找到 .env，使用默认值" -ForegroundColor Yellow
}

$uploadDir = if ($cfg['APP_UPLOAD_DIR']) { $cfg['APP_UPLOAD_DIR'] } else { Join-Path $env:USERPROFILE 'portfolio-media' }
$mysqlHost = if ($cfg['MYSQL_HOST']) { $cfg['MYSQL_HOST'] } else { '127.0.0.1' }
$mysqlPort = if ($cfg['MYSQL_PORT']) { $cfg['MYSQL_PORT'] } else { '3307' }
$mysqlDb   = if ($cfg['MYSQL_DB'])   { $cfg['MYSQL_DB'] }   else { 'CyanWorks' }
$mysqlUser = if ($cfg['MYSQL_USER']) { $cfg['MYSQL_USER'] } else { 'CyanWorks' }
$mysqlPass = $cfg['MYSQL_PASSWORD']

if (-not (Test-Path $uploadDir)) {
  Write-Host "[export] 素材目录不存在：$uploadDir" -ForegroundColor Red
  exit 1
}
if (-not $mysqlPass) {
  Write-Host "[export] .env 里缺少 MYSQL_PASSWORD" -ForegroundColor Red
  exit 1
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$work  = Join-Path $Tool "_migrate_$stamp"
New-Item -ItemType Directory -Path $work -Force | Out-Null
$mediaOut = Join-Path $work 'media'
New-Item -ItemType Directory -Path $mediaOut -Force | Out-Null

# ---- 1. 素材 ----
Write-Host "[export] 复制素材：$uploadDir"
Copy-Item (Join-Path $uploadDir '*') $mediaOut -Recurse -Force -ErrorAction SilentlyContinue
$mediaCount = (Get-ChildItem $mediaOut -Recurse -File).Count
$mediaSize  = [math]::Round(((Get-ChildItem $mediaOut -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "[export] 素材 $mediaCount 个文件，共 $mediaSize MB" -ForegroundColor Green

# ---- 2. 数据库 ----
$dump = Join-Path $work 'cyan.sql'
$mysqldump = Join-Path $Tool 'mysql\bin\mysqldump.exe'
if (-not (Test-Path $mysqldump)) {
  Write-Host "[export] 未找到 mysqldump：$mysqldump" -ForegroundColor Red
  exit 1
}
Write-Host "[export] 导出数据库 $mysqlDb ..."
# 用 mysqldump 自带的 --result-file 直接写盘，不走 PowerShell 重定向：
#   1) `>` / Out-File 会按控制台编码重新解码外部命令输出，容易得到空文件或乱码；
#   2) Start-Process 会把当前进程环境变量塞进大小写不敏感的字典，当系统里同时存在
#      HTTP_PROXY 和 http_proxy（大小写不同）时会直接抛 ArgumentException 失败。
# --no-tablespaces：应用账号没有 PROCESS 权限，不加会额外报一条 Error（不影响数据）。
$dumpArgs = @(
  "-h$mysqlHost", "-P$mysqlPort", "-u$mysqlUser", "-p$mysqlPass",
  '--default-character-set=utf8mb4', '--single-transaction', '--no-tablespaces',
  "--result-file=$dump",
  $mysqlDb
)
# mysqldump 一定会往 stderr 打一条「命令行明文密码不安全」的 Warning。
# 在 $ErrorActionPreference='Stop' 下，PowerShell 5.1 会把外部命令的 stderr 当成
# 终止错误（即便已 2>$null），所以这里临时降级为 Continue，跑完再恢复。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $mysqldump @dumpArgs 2>$null | Out-Null
$ErrorActionPreference = $prevEap
if ((Test-Path $dump) -and ((Get-Item $dump).Length -ge 100)) {
  Write-Host ("[export] 数据库 " + [math]::Round(((Get-Item $dump).Length / 1KB), 1) + " KB") -ForegroundColor Green
} else {
  Write-Host "[export] dump 失败：mysqldump 未产出文件，请检查 .env 里的 MySQL 凭据" -ForegroundColor Red
  exit 1
}

# ---- 3. 打包 ----
$outFile = Join-Path $Repo ("portfolio-migration-$stamp.tar.gz")
$tar = 'tar.exe'
& $tar -czf $outFile -C $work media cyan.sql
if ($LASTEXITCODE -ne 0) {
  Write-Host "[export] 打包失败" -ForegroundColor Red
  exit 1
}

Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '=============================================================================' -ForegroundColor DarkGray
Write-Host " 导出完成：$outFile" -ForegroundColor Green
Write-Host " 素材 $mediaCount 个（$mediaSize MB） + 数据库 $mysqlDb"
Write-Host ''
Write-Host ' 恢复到新机器（Linux 服务器）：' -ForegroundColor Cyan
Write-Host '   tar -xzf portfolio-migration-*.tar.gz -C /tmp'
Write-Host '   mkdir -p /var/lib/portfolio/upload && cp -r /tmp/media/. /var/lib/portfolio/upload/'
Write-Host '   chown -R portfolio:portfolio /var/lib/portfolio/upload'
Write-Host '   mysql -uroot -p CyanWorks < /tmp/cyan.sql'
Write-Host '   systemctl restart portfolio'
Write-Host '=============================================================================' -ForegroundColor DarkGray
