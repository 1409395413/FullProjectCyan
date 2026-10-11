# =============================================================================
#  Initialize schema + app account inside the running local MySQL.
#  Run once after mysql-start.ps1. Safe to re-run (idempotent).
#  Usage: powershell -ExecutionPolicy Bypass -File .\toolchain\mysql-init-db.ps1
# =============================================================================
$ErrorActionPreference = 'Stop'

$Root  = Join-Path $env:USERPROFILE '.toolchain'
$Repo  = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$mysql = Join-Path $Root 'mysql\bin\mysql.exe'
if (-not (Test-Path $mysql)) { Write-Host 'MySQL not installed - run setup-mysql.ps1' -ForegroundColor Red; exit 1 }

# ---- 密码：优先环境变量 / .env ----
$rootPass = $env:MYSQL_ROOT_PASSWORD
$appPass  = $env:MYSQL_PASSWORD
$dbName   = $env:MYSQL_DB
$envFile  = Join-Path $Repo '.env'
if (Test-Path $envFile) {
    if ([string]::IsNullOrWhiteSpace($appPass)) {
        $line = Get-Content $envFile | Where-Object { $_ -match '^\s*MYSQL_PASSWORD\s*=' } | Select-Object -First 1
        if ($line) { $appPass = ($line -split '=', 2)[1].Trim().Trim('"', "'") }
    }
    if ([string]::IsNullOrWhiteSpace($dbName)) {
        $line = Get-Content $envFile | Where-Object { $_ -match '^\s*MYSQL_DB\s*=' } | Select-Object -First 1
        if ($line) { $dbName = ($line -split '=', 2)[1].Trim().Trim('"', "'") }
    }
}
# 仓库不内置明文密码：必须显式提供，否则拒绝建库（避免用公开可见的默认口令建账号）
if ([string]::IsNullOrWhiteSpace($appPass)) { Write-Host 'MYSQL_PASSWORD not set. Set it via env var or repo/.env (copy .env.example -> .env).' -ForegroundColor Red; exit 1 }
if ([string]::IsNullOrWhiteSpace($rootPass)) { $rootPass = $appPass }
if ([string]::IsNullOrWhiteSpace($dbName))   { $dbName   = 'Cyan' }

$sql = @"
CREATE DATABASE IF NOT EXISTS $dbName CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'Cyan'@'%' IDENTIFIED BY '$appPass';
GRANT SELECT,INSERT,UPDATE,DELETE ON $dbName.* TO 'Cyan'@'%';
FLUSH PRIVILEGES;
"@
$sqlTmp = Join-Path $Root '_init.sql'
Set-Content -Path $sqlTmp -Value $sql -Encoding ASCII

Write-Host '[mysql] creating database and app account ...'
Get-Content $sqlTmp | & $mysql -h127.0.0.1 -P3307 -uroot "-p$rootPass" 2>&1 | Out-Null

$schema = Join-Path $Repo 'server\src\main\resources\db\schema.sql'
if (Test-Path $schema) {
    Write-Host '[mysql] applying schema.sql ...'
    Get-Content $schema | & $mysql -h127.0.0.1 -P3307 -uCyan "-p$appPass" $dbName 2>&1 | Out-Null
} else {
    Write-Host ('[mysql] schema not found: ' + $schema) -ForegroundColor Yellow
}

Remove-Item $sqlTmp -Force -ErrorAction SilentlyContinue
Write-Host ''
Write-Host ('[mysql] database : ' + $dbName)
Write-Host ('[mysql] connect  : mysql -h127.0.0.1 -P3307 -uCyan -p**** ' + $dbName)
Write-Host ('[mysql] jdbc     : jdbc:mysql://127.0.0.1:3307/' + $dbName)
Write-Host ''
Write-Host 'Security reminder: also set the root password once:' -ForegroundColor Yellow
Write-Host '  ALTER USER ''root''@''localhost'' IDENTIFIED BY ''<strong-password>'';' -ForegroundColor Yellow
