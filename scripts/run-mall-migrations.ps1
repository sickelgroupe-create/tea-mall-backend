[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_]+$')][string]$Database,
    [string]$DatabaseHost = '127.0.0.1',
    [ValidateRange(1, 65535)][int]$Port = 3306,
    [string]$User = 'root',
    [string]$MySql = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $PSScriptRoot 'mall-migrations.json'
$sqlRoot = Join-Path $projectRoot 'sql'

if (-not (Test-Path -LiteralPath $MySql -PathType Leaf)) {
    throw "MySQL client not found: $MySql"
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw 'Migration manifest is missing'
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
$mysqlArgs = @(
    '--no-defaults',
    '--default-character-set=utf8mb4',
    '--protocol=tcp',
    "--host=$DatabaseHost",
    "--port=$Port",
    "--user=$User",
    "--database=$Database",
    '--batch',
    '--raw',
    '--skip-column-names'
)

# 密码只从当前进程环境读取，不接受命令行明文参数，也不打印。
$previousMySqlPassword = $env:MYSQL_PWD
try {
    if ($env:CHAYE_DB_PASSWORD) { $env:MYSQL_PWD = $env:CHAYE_DB_PASSWORD }
    $tableExists = (& $MySql @mysqlArgs --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mall_schema_migration'").Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect migration registry' }
    if ($tableExists -ne '1') { throw 'mall_schema_migration is missing; initialize the base schema before incremental migration' }

    $appliedRows = & $MySql @mysqlArgs --execute='SELECT version_no FROM mall_schema_migration'
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read migration registry' }
    $applied = @{}
    foreach ($row in @($appliedRows)) { if ($row) { $applied[$row.Trim()] = $true } }

    $pending = @()
    foreach ($migration in $manifest.migrations) {
        $file = Join-Path $sqlRoot $migration.file
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing migration: $($migration.file)" }
        $actualHash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $migration.sha256) { throw "Migration checksum mismatch: $($migration.file)" }
        if (-not $applied.ContainsKey([string]$migration.version)) { $pending += $migration }
    }

    if ($pending.Count -eq 0) {
        [pscustomobject]@{ status = 'PASS'; database = $Database; applied = 0; message = 'No pending migrations' } | ConvertTo-Json -Compress
        exit 0
    }

    $parts = [System.Collections.Generic.List[string]]::new()
    # The named lock is held by this single mysql connection for the complete SQL stream.
    # GET_LOCK waits up to 30 seconds, serializing concurrent deployment processes.
    $parts.Add("SELECT GET_LOCK(CONCAT('mall-migrate-',DATABASE()),30);")
    foreach ($migration in $pending) {
        $parts.Add((Get-Content -LiteralPath (Join-Path $sqlRoot $migration.file) -Raw -Encoding utf8))
    }
    $parts.Add("SELECT RELEASE_LOCK(CONCAT('mall-migrate-',DATABASE()));")
    $sql = [string]::Join([Environment]::NewLine, $parts)
    $migrationOutput = $sql | & $MySql @mysqlArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        $lastError = @($migrationOutput | Where-Object { $_ }) | Select-Object -Last 1
        throw "Migration execution failed and deployment must stop: $lastError"
    }

    $missing = @()
    foreach ($migration in $pending) {
        $count = (& $MySql @mysqlArgs --execute="SELECT COUNT(*) FROM mall_schema_migration WHERE version_no='$($migration.version)'").Trim()
        if ($count -ne '1') { $missing += $migration.version }
    }
    if ($missing.Count) { throw "Migration registry verification failed: $($missing -join ',')" }
    [pscustomobject]@{ status = 'PASS'; database = $Database; applied = $pending.Count; versions = @($pending.version) } | ConvertTo-Json -Compress
}
finally {
    if ($null -eq $previousMySqlPassword) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    else { $env:MYSQL_PWD = $previousMySqlPassword }
}
