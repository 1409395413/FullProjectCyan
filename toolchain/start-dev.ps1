# =============================================================================
#  One-shot local dev:  Redis + Spring Boot API + Vue frontend
#  Usage: powershell -ExecutionPolicy Bypass -File .\toolchain\start-dev.ps1
#  Stop : close the spawned windows, then .\toolchain\redis-stop.ps1
# =============================================================================
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# ---- 1. toolchain into this session ----
$Root = Join-Path $env:USERPROFILE '.toolchain'
$add  = @((Join-Path $Root 'jdk-17\bin'), (Join-Path $Root 'maven\bin'), (Join-Path $Root 'redis'))
foreach ($p in $add) { if (Test-Path $p -and ($env:Path -notlike "*$p*")) { $env:Path = $p + ';' + $env:Path } }
$env:JAVA_HOME = Join-Path $Root 'jdk-17'
$env:M2_HOME   = Join-Path $Root 'maven'

# 载入仓库根 .env 到本会话 —— 子进程（后端 / 前端）会继承这些变量。
# 必须加载：application.yml 里 MYSQL_PORT 的默认值是 3306（生产标准端口），
# 而本机便携 MySQL 跑在 3307，不注入环境变量后端会连错库。
. (Join-Path $PSScriptRoot 'load-env.ps1') | Out-Null
if (-not $env:MYSQL_PORT) { $env:MYSQL_PORT = '3307' }
Write-Host "[dev] MYSQL_PORT=$env:MYSQL_PORT  REDIS_PORT=$env:REDIS_PORT" -ForegroundColor DarkGray

Write-Host '[dev] starting MySQL (port 3307) ...' -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'mysql-start.ps1')

Write-Host '[dev] starting Redis (cache / lock / rate limit) ...' -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'redis-start.ps1')

Write-Host '[dev] starting backend (Spring Boot, port 8080) ...' -ForegroundColor Cyan
Start-Process powershell -ArgumentList @(
    '-NoExit', '-NoProfile',
    '-Command',
    "cd '$Repo\server'; `$env:Path='$((Join-Path $Root 'jdk-17\bin'));$((Join-Path $Root 'maven\bin'));' + `$env:Path; `$env:JAVA_HOME='$((Join-Path $Root 'jdk-17'))'; mvn -q spring-boot:run"
)

Write-Host '[dev] starting frontend (Vite, port 5173) ...' -ForegroundColor Cyan
Start-Process powershell -ArgumentList @(
    '-NoExit', '-NoProfile',
    '-Command',
    "cd '$Repo\web'; npm install; npm run dev"
)

Write-Host ''
Write-Host '-------------------------------------------------------' -ForegroundColor DarkGray
Write-Host '  frontend  http://localhost:5173' -ForegroundColor Green
Write-Host '  api       http://127.0.0.1:8080/api/content' -ForegroundColor Green
Write-Host '  health    http://127.0.0.1:8080/actuator/health' -ForegroundColor Green
Write-Host '-------------------------------------------------------' -ForegroundColor DarkGray
