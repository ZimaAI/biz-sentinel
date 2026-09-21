param([ValidateSet('database','backend','frontend','credentials')][string]$Action = 'database')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localPath = Join-Path $projectRoot '.commerce-local'
New-Item -ItemType Directory -Path $localPath -Force | Out-Null
$envPath = Join-Path $localPath 'development.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    $settings = [ordered]@{}
    foreach ($name in @('MYSQL_ROOT_PASSWORD','COMMERCE_MANAGEMENT_PASSWORD','COMMERCE_INGESTION_PASSWORD','COMMERCE_QUERY_PASSWORD','COMMERCE_ADMIN_PASSWORD')) {
        $settings[$name] = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N').Substring(0,8)
    }
    $settings['COMMERCE_INITIALIZE_SCHEMA'] = 'true'
    $settings['COMMERCE_FIXTURE_DIRECTORY'] = Join-Path $projectRoot 'docs/reference/commerce-lens/fixtures/business/t_demo'
    $settings['COMMERCE_STORAGE_PATH'] = Join-Path $localPath 'ingestions'
    $settings.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" } | Set-Content -LiteralPath $envPath -Encoding utf8
}
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') }
}
switch ($Action) {
    'database' {
        $existing = docker ps -a --filter 'name=^biz-sentinel-commerce-mysql$' --format '{{.Names}}'
        if ($existing) { docker start biz-sentinel-commerce-mysql | Out-Null }
        else {
            $initPath = Join-Path $localPath 'init.sql'
            @"
CREATE DATABASE IF NOT EXISTS commerce_management CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS commerce_analysis CHARACTER SET utf8mb4;
CREATE USER 'cl_management'@'%' IDENTIFIED BY '$env:COMMERCE_MANAGEMENT_PASSWORD';
CREATE USER 'cl_ingestion'@'%' IDENTIFIED BY '$env:COMMERCE_INGESTION_PASSWORD';
CREATE USER 'cl_query'@'%' IDENTIFIED BY '$env:COMMERCE_QUERY_PASSWORD';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,INDEX,REFERENCES ON commerce_management.* TO 'cl_management'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,INDEX,REFERENCES ON commerce_analysis.* TO 'cl_ingestion'@'%';
GRANT SELECT ON commerce_analysis.* TO 'cl_query'@'%';
"@ | Set-Content -LiteralPath $initPath -Encoding utf8
            docker run -d --name biz-sentinel-commerce-mysql --env-file $envPath -p 127.0.0.1:13316:3306 --mount "type=bind,source=$initPath,target=/docker-entrypoint-initdb.d/001-commerce.sql,readonly" --mount 'type=volume,source=biz-sentinel-commerce-mysql,target=/var/lib/mysql' mysql:8.4 | Out-Null
        }
        Write-Output 'Commerce MySQL started on 127.0.0.1:13316. Run backend after initialization completes.'
    }
    'backend' {
        Set-Location $projectRoot
        & mvn.cmd -pl data-agent-management '-DskipTests' '-Dcheckstyle.skip' '-Dspotless.skip=true' spring-boot:run '-Dspring-boot.run.profiles=commerce'
    }
    'frontend' {
        Set-Location (Join-Path $projectRoot 'data-agent-frontend-nuxt')
        & pnpm.cmd dev --host 127.0.0.1
    }
    'credentials' { Write-Output "Username: admin`nPassword: $env:COMMERCE_ADMIN_PASSWORD" }
}
