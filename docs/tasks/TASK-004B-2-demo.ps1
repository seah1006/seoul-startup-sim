# TASK-004B 파트 2 데모: 게임 3개를 엔딩까지 진행 → 관리자 "지금 갱신"으로 랭킹 배치 수동 실행 → curl.exe -i 로 랭킹·요약·공유 확인
# 전제: worktree 전용 DB(5442)·앱(8090) 실행, 데모 상권(DEMO_A, DEMO_B)과 6분기(20231~20242) 픽스처 적재.
# 토큰·관리자 비밀번호는 출력하지 않는다(.env에서 읽기만 한다).
param([string]$Base = 'http://localhost:8090')
$ErrorActionPreference = 'Stop'

function Api($method, $path, $token, $body, $key) {
    $headers = @{ 'X-Guest-Token' = $token }
    if ($key) { $headers['Idempotency-Key'] = $key }
    $json = if ($null -ne $body) { $body | ConvertTo-Json -Compress } else { $null }
    Invoke-RestMethod -Method $method -Uri "$Base$path" -Headers $headers -Body $json -ContentType 'application/json; charset=utf-8'
}

function NewGame($token, $owner, $area, $lng, $shop) {
    $game = Api POST '/api/games' $token @{ ownerName = $owner; background = 'OFFICE_WORKER'; candidateId = 'c1'; candidateSeed = 20261118; focusStats = @('service'); startQuarter = '20231' }
    Api POST "/api/games/$($game.gameId)/shop" $token @{ areaCode = $area; serviceCode = 'CS100010'; lng = $lng; lat = 38; shopName = $shop } | Out-Null
    $game.gameId
}

# 분기 하나 진행. 전환점이 뜨면 첫 선택지를 고른다.
function Quarter($token, $id, $marketing) {
    $state = Api GET "/api/games/$id" $token
    Api PUT "/api/games/$id/turns/$($state.currentTurn)/decision" $token @{ priceLevel = 'NORMAL'; openHours = 10; staff = 2; marketingBudget = $marketing; ownerActivity = 'MENU_DEV' } | Out-Null
    $settled = Api POST "/api/games/$id/advance" $token @{ expectedVersion = $state.version } ([guid]::NewGuid())
    if ($settled.game.pendingTurningPoint) {
        $point = Api GET "/api/games/$id/turning-point" $token
        Api POST "/api/games/$id/turning-point/choice" $token @{ optionId = $point.options[0].optionId } ([guid]::NewGuid()) | Out-Null
        Write-Host "  전환점 $($point.code) → $($point.options[0].label)"
    }
    $settled
}

$guest = Invoke-RestMethod -Method POST -Uri "$Base/api/guest"
$token = $guest.guestToken
Write-Host "게스트 닉네임: $($guest.nickname)"

# 1) LONG_RUNNING: 마지막 분기(20242)까지
$long = NewGame $token '김장수' 'DEMO_A' 127 '데모 장수커피'
do { $r = Quarter $token $long 1000000 } while ($r.game.status -eq 'PLAYING')
Write-Host "게임 $long : $($r.game.endingType)"

# 2) SOLD: 4분기 영업 뒤 매각
$sold = NewGame $token '박매각' 'DEMO_B' 128.5 '데모 매각커피'
1..4 | ForEach-Object { Quarter $token $sold 1000000 | Out-Null }
$state = Api GET "/api/games/$sold" $token
$sale = Api POST "/api/games/$sold/sell" $token @{ expectedVersion = $state.version } ([guid]::NewGuid())
Write-Host "게임 $sold : $($sale.endingType) (매각 전 잔액 $($state.cash) → 후 $($sale.cash))"

# 3) CLOSED: 과한 마케팅비로 2분기 연속 적자 잔액
$closed = NewGame $token '이폐업' 'DEMO_A' 127 '데모 폐업커피'
do { $r = Quarter $token $closed 200000000 } while ($r.game.status -eq 'PLAYING')
Write-Host "게임 $closed : $($r.game.endingType)"

# 관리자 로그인 → 지금 갱신 (배치 수동 실행, 같은 advisory lock)
$envFile = Join-Path $PSScriptRoot '..\..\.env'
$vars = @{}
Get-Content $envFile | Where-Object { $_ -match '^(ADMIN_USERNAME|ADMIN_PASSWORD)=' } | ForEach-Object { $k, $v = $_ -split '=', 2; $vars[$k] = $v }
$jar = Join-Path ([IO.Path]::GetTempPath()) 'sss-004b2-admin.cookies'
Remove-Item $jar -ErrorAction SilentlyContinue
$login = curl.exe -s -c $jar "$Base/admin/login"
$csrf = [regex]::Match(($login -join ''), 'name="_csrf" value="([^"]+)"').Groups[1].Value
curl.exe -s -o NUL -b $jar -c $jar --data-urlencode "username=$($vars.ADMIN_USERNAME)" --data-urlencode "password=$($vars.ADMIN_PASSWORD)" --data-urlencode "_csrf=$csrf" "$Base/admin/login"
$page = curl.exe -s -b $jar -c $jar "$Base/admin/rankings"
$csrf = [regex]::Match(($page -join ''), 'name="_csrf" value="([^"]+)"').Groups[1].Value
Write-Host '--- POST /admin/rankings/refresh'
curl.exe -s -i -b $jar -c $jar --data-urlencode "_csrf=$csrf" "$Base/admin/rankings/refresh" | Select-String '^HTTP|^Location'
$page = curl.exe -s -b $jar "$Base/admin/rankings"
Write-Host ('관리자 화면 마지막 갱신: ' + [regex]::Match(($page -join ''), 'id="last-refreshed">([^<]+)<').Groups[1].Value)
Remove-Item $jar -ErrorAction SilentlyContinue

Write-Host '--- curl.exe -i 랭킹 ALL'
curl.exe -s -i "$Base/api/rankings?kind=ALL"
Write-Host "`n--- curl.exe -i 랭킹 INDUSTRY (CS100010)"
curl.exe -s -i "$Base/api/rankings?kind=INDUSTRY&key=CS100010&limit=2"
Write-Host "`n--- curl.exe -i 랭킹 AREA key 누락"
curl.exe -s -i "$Base/api/rankings?kind=AREA"
Write-Host "`n--- curl.exe -i 요약 (게임 $sold)"
curl.exe -s -i -H "X-Guest-Token: $token" "$Base/api/games/$sold/summary"
Write-Host "`n--- curl.exe -i 공유 HTML (게임 $long, 로그인 없음)"
curl.exe -s -i "$Base/share/$long"
Write-Host "`n--- curl.exe -i 공유 없는 게임"
curl.exe -s -i "$Base/share/999999" | Select-String '^HTTP|찾을 수 없습니다'
