param([Parameter(Mandatory)][string]$Server,[Parameter(Mandatory)][string]$Key,[string]$RemoteDir='sss',[string]$SourceContainer='seoul-startup-sim-db-1')
$ErrorActionPreference='Stop'
if ($RemoteDir -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid RemoteDir' }
function Run([scriptblock]$Command) { & $Command; if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE)" } }
$dump=Join-Path ([IO.Path]::GetTempPath()) ('sss-data-'+[guid]::NewGuid()+'.dump')
$watch=[Diagnostics.Stopwatch]::StartNew()
try {
 Run { docker exec $SourceContainer sh -ec 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-acl -f /tmp/sss-export.dump' }
 Run { docker cp "${SourceContainer}:/tmp/sss-export.dump" $dump }
 Write-Host ('Dump: {0:N1} MB, {1:N1}s' -f ((Get-Item $dump).Length/1MB),$watch.Elapsed.TotalSeconds)
 Run { scp -i $Key -q $dump "${Server}:/tmp/sss-data.dump" }
 $remote=@(
 'set -e',
 "cd ~/$RemoteDir",
 'docker compose -p sss -f docker-compose.serve.yml stop app caddy',
 'docker compose -p sss -f docker-compose.serve.yml up -d --wait db',
 'id=$(docker compose -p sss -f docker-compose.serve.yml ps -q db)',
 'docker cp /tmp/sss-data.dump "$id:/tmp/sss-data.dump"',
 'docker exec "$id" sh -ec ''pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-acl --exit-on-error /tmp/sss-data.dump''',
 'rm -f /tmp/sss-data.dump; docker exec "$id" rm -f /tmp/sss-data.dump',
 'docker compose -p sss -f docker-compose.serve.yml up -d --wait'
 ) -join "`n"
 Run { ssh -i $Key $Server $remote }
 Write-Host ('Completed: {0:N1}s' -f $watch.Elapsed.TotalSeconds)
} finally {
 Remove-Item $dump -Force -ErrorAction SilentlyContinue
 docker exec $SourceContainer rm -f /tmp/sss-export.dump | Out-Null
}
