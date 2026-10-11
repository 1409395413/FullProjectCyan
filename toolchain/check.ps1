# =============================================================================
#  Health check for the local toolchain
#  Usage: powershell -ExecutionPolicy Bypass -File .\toolchain\check.ps1
# =============================================================================
$Root     = Join-Path $env:USERPROFILE '.toolchain'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# 当前会话里补上工具链路径 —— 装完后没重开终端时也能体检
foreach ($p in @((Join-Path $Root 'jdk-17\bin'), (Join-Path $Root 'maven\bin'), (Join-Path $Root 'redis'), (Join-Path $Root 'mysql\bin'))) {
    if ((Test-Path $p) -and ($env:Path -notlike ('*' + $p + '*'))) { $env:Path = $p + ';' + $env:Path }
}

# ---- .env 读取小工具 ----
function EnvFrom($file, $key) {
    if (-not (Test-Path $file)) { return $null }
    $line = Get-Content $file | Where-Object { $_ -match ('^\s*' + $key + '\s*=') } | Select-Object -First 1
    if ($line) { return ($line -split '=', 2)[1].Trim().Trim('"', "'") }
    return $null
}
$envFile = Join-Path $repoRoot '.env'

Write-Host ''
Write-Host '------------------------- TOOLS -------------------------' -ForegroundColor DarkGray
foreach ($item in @(
    @{ Name = 'java';      Hint = 'toolchain\setup.ps1 (JDK 17)' },
    @{ Name = 'javac';     Hint = 'toolchain\setup.ps1 (JDK 17)' },
    @{ Name = 'mvn';       Hint = 'toolchain\setup.ps1 (Maven 3.9.11)' },
    @{ Name = 'redis-cli'; Hint = 'toolchain\setup.ps1 (Redis 5.0.14)' },
    @{ Name = 'node';      Hint = 'Node.js 20+ LTS' },
    @{ Name = 'npm';       Hint = 'ships with Node.js' }
)) {
    $cmd = Get-Command $item.Name -ErrorAction SilentlyContinue
    if ($cmd) {
        $ver = switch ($item.Name) {
            'java'      { (& java -version 2>&1 | Select-Object -First 1) }
            'javac'     { (& javac -version 2>&1 | Select-Object -First 1) }
            'mvn'       { (& mvn -v 2>&1 | Select-Object -First 1) }
            'node'      { (& node -v) }
            'npm'       { (& npm -v) }
            default     { 'available' }
        }
        Write-Host ("  {0,-11} OK     {1}" -f $item.Name, $ver) -ForegroundColor Green
    } else {
        Write-Host ("  {0,-11} MISSING  {1}" -f $item.Name, $item.Hint) -ForegroundColor Yellow
    }
}

 Write-Host ''
Write-Host '------------------------- MYSQL -------------------------' -ForegroundColor DarkGray
$mysqld = Join-Path $Root 'mysql\bin\mysqld.exe'
$mysqlc = Join-Path $Root 'mysql\bin\mysql.exe'
if (Test-Path $mysqld) {
    Write-Host ('  binary      OK     ' + (Split-Path (Split-Path $mysqld -Parent) -Parent)) -ForegroundColor Green
    $p = Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue
    if ($p) {
        Write-Host ('  port 3307   UP     pid=' + $p.OwningProcess) -ForegroundColor Green
        if (Test-Path $mysqlc) {
            $pw = $env:MYSQL_PASSWORD
            if ([string]::IsNullOrWhiteSpace($pw)) { $pw = EnvFrom $envFile 'MYSQL_PASSWORD' }
            # 仓库不内置明文密码；未配置时留空并提示，而不是猜一个默认值
            if ([string]::IsNullOrWhiteSpace($pw)) { $pw = ''; Write-Host '  tables      (skip: MYSQL_PASSWORD 未配置)' -ForegroundColor DarkYellow }
            $env:MYSQL_PWD = $pw
            $tables = (& $mysqlc -h127.0.0.1 -P3307 -uCyan -N -e 'SHOW TABLES FROM CyanWorks' 2>$null)
            Write-Host ('  tables      ' + (($tables -join ' ').Trim()))
        }
    } else {
        Write-Host '  port 3307   DOWN   start it with toolchain\mysql-start.ps1' -ForegroundColor Yellow
    }
} else {
    Write-Host '  not installed - run toolchain\setup-mysql.ps1' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '------------------------- REDIS -------------------------' -ForegroundColor DarkGray
$cli = Join-Path $Root 'redis\redis-cli.exe'
if (Test-Path $cli) {
    Write-Host '  binary      OK' -ForegroundColor Green
    $pass = $env:REDIS_PASSWORD
    if ([string]::IsNullOrWhiteSpace($pass)) { $pass = EnvFrom $envFile 'REDIS_PASSWORD' }
    # 仓库不内置明文密码；未配置时留空并提示，仍尝试无密码连接
    if ([string]::IsNullOrWhiteSpace($pass)) { $pass = ''; Write-Host '  password    (warn: REDIS_PASSWORD 未配置)' -ForegroundColor DarkYellow }
    $env:REDISCLI_AUTH = $pass
    $pong = (& $cli -h 127.0.0.1 ping 2>$null) -join ' '
    if ($pong -like '*PONG*') {
        Write-Host '  ping        PONG' -ForegroundColor Green
        Write-Host ('  dbsize      ' + ((& $cli -h 127.0.0.1 dbsize 2>$null) -join ' '))
    } else {
        Write-Host '  NOT running - start it with toolchain\redis-start.ps1' -ForegroundColor Yellow
    }
} else {
    Write-Host '  not installed - run toolchain\setup.ps1' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '------------------------- PATH ENV ----------------------' -ForegroundColor DarkGray
Write-Host ('  JAVA_HOME  = ' + $env:JAVA_HOME)
Write-Host ('  M2_HOME    = ' + $env:M2_HOME)
Write-Host ('  Toolchain  = ' + $Root)
Write-Host ''
