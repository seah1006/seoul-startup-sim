param([Parameter(Mandatory)][string]$Server,[Parameter(Mandatory)][string]$Key,[string]$RemoteDir='sss',[switch]$StageOnly)
$ErrorActionPreference='Stop'
if ($RemoteDir -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid RemoteDir' }
function Run([scriptblock]$Command) { & $Command; if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE)" } }
$root=Split-Path $PSScriptRoot -Parent
$bundle=Join-Path ([IO.Path]::GetTempPath()) ('sss-'+[guid]::NewGuid()+'.tgz')
try {
 Push-Location "$root/backend"
 try { Run { .\gradlew.bat bootJar --offline } } finally { Pop-Location }
 Push-Location "$root/frontend"
 $previousMock=$env:VITE_USE_MOCK
 try { $env:VITE_USE_MOCK='false'; Run { npm ci --offline }; Run { npm run build } }
 finally { $env:VITE_USE_MOCK=$previousMock; Pop-Location }
 $jar=Get-ChildItem "$root/backend/build/libs/*.jar" | Where-Object Name -NotLike '*-plain.jar' | Select-Object -First 1
 if (!$jar) { throw 'bootJar missing' }
 Copy-Item $jar.FullName "$PSScriptRoot/app.jar"
 New-Item -ItemType Directory -Force "$PSScriptRoot/frontend" | Out-Null
 Copy-Item "$root/frontend/dist" "$PSScriptRoot/frontend/" -Recurse -Force
 Run { tar -czf $bundle -C $PSScriptRoot app.jar frontend/dist docker-compose.serve.yml Caddyfile }
 Run { scp -i $Key -q $bundle "${Server}:/tmp/sss-deploy.tgz" }
 $start=if($StageOnly){'up -d db'}else{'up -d --force-recreate app caddy'}
 $remote="set -e; mkdir -p ~/$RemoteDir; cd ~/$RemoteDir; test -f .env; chmod 600 .env; tar -xzf /tmp/sss-deploy.tgz; rm /tmp/sss-deploy.tgz; docker compose -p sss -f docker-compose.serve.yml $start"
 Run { ssh -i $Key $Server $remote }
} finally {
 Remove-Item $bundle -Force -ErrorAction SilentlyContinue
 Remove-Item "$PSScriptRoot/app.jar" -Force -ErrorAction SilentlyContinue
 Remove-Item "$PSScriptRoot/frontend" -Recurse -Force -ErrorAction SilentlyContinue
}
