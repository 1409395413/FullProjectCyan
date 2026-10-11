# =============================================================================
#  换设备 / 换服务器：一键恢复「素材 + 数据库」
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File .\toolchain\import-for-migration.ps1
#    powershell -ExecutionPolicy Bypass -File .\toolchain\import-for-migration.ps1 -Package D:\backup\portfolio-migration-20261008.tar.gz
#
#  前置：
#    1) 已在当前机器跑过 .\toolchain\setup.ps1（本地 MySQL / Redis 就绪）
#    2) MySQL 服务已启动
#
#  它会做三件事：
#    a) 把 media/ 下的素材按原相对路径（2026/10/xxx.mp4）还原到 APP_UPLOAD_DIR
#    b) 建库（如不存在）+ 导入 cyan.sql
#    c) 校验：素材文件数 vs media_asset 表行数，不一致会告警
#
#  注意：APP_UPLOAD_DIR 必须是「项目目录之外」的绝对路径，
#        这样重新 clone / 重新打包部署都不会让 /media/** 变 404。
# =============================================================================
param(
    [string]$Package,
    [string]$UploadDir
)

$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Tool = Join-Path $env:USERPROFILE '.toolchain'

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
    Write-Host "[import] 已读取 $envFile"
}

if (-not $UploadDir) { $UploadDir = $cfg['APP_UPLOAD_DIR'] }
if (-not $UploadDir) { $UploadDir = Join-Path $env:USERPROFILE 'portfolio-media' }
$mysqlHost = if ($cfg['MYSQL_HOST']) { $cfg['MYSQL_HOST'] } else { '127.0.0.1' }
$mysqlPort = if ($cfg['MYSQL_PORT']) { $cfg['MYSQL_PORT'] } else { '3307' }
$mysqlDb   = if ($cfg['MYSQL_DB']) { $cfg['MYSQL_DB'] } else { 'CyanWorks' }
$mysqlUser = if ($cfg['MYSQL_USER']) { $cfg['MYSQL_USER'] } else { 'CyanWorks' }
$mysqlPass = $cfg['MYSQL_PASSWORD']

# ---- 定位迁移包 ----
if (-not $Package) {
    $found = Get-ChildItem $Repo -Filter 'portfolio-migration-*.tar.gz' -File -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending |
             Select-Object -First 1
    if (-not $found) {
        Write-Host "[import] 仓库根目录没找到 portfolio-migration-*.tar.gz" -ForegroundColor Red
        exit 1
    }
    $Package = $found.FullName
}
if (-not (Test-Path $Package)) {
    Write-Host "[import] 迁移包不存在：$Package" -ForegroundColor Red
    exit 1
}
Write-Host "[import] 迁移包：$Package"

# ---- 解压 ----
$work = Join-Path $Tool ("_restore_" + (Get-Date -Format 'yyyyMMdd-HHmm'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
& tar.exe -xzf $Package -C $work
if ($LASTEXITCODE -ne 0) { Write-Host "[import] 解压失败" -ForegroundColor Red; exit 1 }

$mediaIn = Join-Path $work 'media'
$sqlPath = Join-Path $work 'cyan.sql'
if (-not (Test-Path $mediaIn)) { Write-Host "[import] 包里缺少 media/" -ForegroundColor Red; exit 1 }
if (-not (Test-Path $sqlPath)) { Write-Host "[import] 包里缺少 cyan.sql" -ForegroundColor Red; exit 1 }

# ---- 1. 还原素材 ----
Write-Host "[import] 还原素材 → $UploadDir"
New-Item -ItemType Directory -Path $UploadDir -Force | Out-Null
Copy-Item (Join-Path $mediaIn '*') $UploadDir -Recurse -Force
$mediaCount = (Get-ChildItem $UploadDir -Recurse -File).Count
$mediaSize = [math]::Round(((Get-ChildItem $UploadDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "[import] 素材 $mediaCount 个文件，共 $mediaSize MB" -ForegroundColor Green

# ---- 2. 还原数据库 ----
$mysql = Join-Path $Tool 'mysql\bin\mysql.exe'
if (-not (Test-Path $mysql)) { Write-Host "[import] 未找到 mysql 客户端：$mysql" -ForegroundColor Red; exit 1 }

Write-Host "[import] 建库 $mysqlDb（如不存在）..."
$createSql = "CREATE DATABASE IF NOT EXISTS ``$mysqlDb`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $mysql "-h$mysqlHost" "-P$mysqlPort" "-u$mysqlUser" "-p$mysqlPass" '-e' $createSql 2>$null | Out-Null
$ErrorActionPreference = $prevEap

Write-Host "[import] 导入 cyan.sql ..."
# PowerShell 5.1 没有 `<` 输入重定向，管道喂大 SQL 又慢又容易踩编码坑，
# 所以这里直接用 .NET Process 把文件字节流写进 mysql 的 stdin。
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $mysql
$psi.Arguments = "-h$mysqlHost -P$mysqlPort -u$mysqlUser -p$mysqlPass --default-character-set=utf8mb4 $mysqlDb"
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true
$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
[void]$proc.Start()
$bytes = [System.IO.File]::ReadAllBytes($sqlPath)
$proc.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
$proc.StandardInput.BaseStream.Flush()
$proc.StandardInput.Close()
$stdout = $proc.StandardOutput.ReadToEnd()
$stderr = $proc.StandardError.ReadToEnd()
$proc.WaitForExit()

if ($proc.ExitCode -ne 0) {
    Write-Host "[import] 导入失败（exit=$($proc.ExitCode)）：" -ForegroundColor Red
    if ($stderr) { Write-Host ($stderr -split "`n" | Select-Object -Last 5 | Out-String) -ForegroundColor Red }
    exit 1
}
Write-Host ("[import] 数据库导入完成（" + [math]::Round(((Get-Item $sqlPath).Length / 1KB), 1) + " KB）") -ForegroundColor Green

# ---- 3. 校验 ----
Write-Host "[import] 校验素材数 vs media_asset 行数 ..."
$dbCount = $null
try {
    $q = "SELECT COUNT(*) FROM media_asset;"
    $ErrorActionPreference = 'Continue'
    # 末尾必须带上库名，否则 mysql 会报 "No database selected"，静默拿不到行数。
    $dbCount = (& $mysql "-h$mysqlHost" "-P$mysqlPort" "-u$mysqlUser" "-p$mysqlPass" '--default-character-set=utf8mb4' '-N' '-B' '-e' $q $mysqlDb 2>$null | Select-Object -Last 1)
    $ErrorActionPreference = $prevEap
} catch {
    $ErrorActionPreference = $prevEap
}
if ($dbCount -and ($dbCount -as [int]) -ne $null) {
    $n = [int]$dbCount
    if ($n -eq $mediaCount) {
        Write-Host "[import] 一致：数据库 $n 条，素材 $mediaCount 个" -ForegroundColor Green
    } else {
        Write-Host "[import] 不一致：数据库 $n 条，素材 $mediaCount 个（可能有多余历史文件，不影响访问）" -ForegroundColor Yellow
    }
}

Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '=============================================================================' -ForegroundColor DarkGray
Write-Host ' 恢复完成。接下来：' -ForegroundColor Cyan
Write-Host '   1) 确认 .env 里 APP_UPLOAD_DIR 指向上面那个目录（绝对路径，且在项目目录之外）'
Write-Host '   2) 重启后端：powershell -File .\toolchain\start-dev.ps1'
Write-Host '   3) 打开 http://127.0.0.1:5173 抽查几个视频能不能播'
Write-Host '=============================================================================' -ForegroundColor DarkGray
