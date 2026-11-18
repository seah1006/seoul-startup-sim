param([string]$BaseUrl = 'http://localhost:8089')
$ErrorActionPreference = 'Stop'
# 토큰과 비밀번호 응답은 파일로만 받아 메모리에서 사용하고 화면에 출력하지 않는다.
$temporary = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
New-Item -ItemType Directory $temporary | Out-Null
function Request([string]$Method, [string]$Route, $Body = $null, [string]$Token = '') {
    $output = Join-Path $temporary 'response.txt'
    $headers = Join-Path $temporary 'request-headers.txt'
    $arguments = @('-sS', '-i', '-X', $Method, "$BaseUrl$Route", '-o', $output)
    if ($Token) {
        [IO.File]::WriteAllText($headers, "Authorization: Bearer $Token`n")
        $arguments += @('-H', "@$headers")
    }
    if ($null -ne $Body) {
        $inputFile = Join-Path $temporary 'request.json'
        [IO.File]::WriteAllText($inputFile, ($Body | ConvertTo-Json -Compress))
        $arguments += @('-H', 'Content-Type: application/json', '--data-binary', "@$inputFile")
    }
    & curl.exe @arguments
    if ($LASTEXITCODE -ne 0) { throw 'HTTP 요청 실패' }
    $raw = [IO.File]::ReadAllText($output)
    $status = [regex]::Match($raw, 'HTTP/\S+ (\d+)').Groups[1].Value
    Write-Host "$Method $Route -> $status"
    if ([int]$status -ge 400) { throw "예상하지 않은 HTTP 상태: $status" }
    $parts = $raw -split '\r?\n\r?\n', 2
    if ($parts.Length -gt 1 -and $parts[1].Trim()) { return $parts[1] | ConvertFrom-Json }
}
try {
    $guest = Request 'POST' '/api/guest'
    $game = Request 'POST' '/api/games' @{ ownerName = '시연사장'; background = 'OFFICE_WORKER'; candidateId = 'c1'; candidateSeed = 123; focusStats = @('marketing', 'service') } $guest.guestToken
    $loginId = 'demo_' + [guid]::NewGuid().ToString('N').Substring(0, 10)
    $password = [guid]::NewGuid().ToString('N') + 'a1'
    $member = Request 'POST' '/api/auth/signup' @{ loginId = $loginId; password = $password; nickname = $loginId.Substring(0, 12) } $guest.guestToken
    if ($member.user.userId -ne $guest.userId) { throw '전환 사용자 ID 불일치' }
    $gameId = $game.gameId
    $same = Request 'GET' "/api/games/$gameId" $null $member.token
    if ($same.gameId -ne $gameId) { throw '게임 유지 실패' }
    Request 'POST' '/api/auth/logout' $null $member.token
    $session = Request 'POST' '/api/auth/login' @{ loginId = $loginId; password = $password }
    $null = Request 'PATCH' '/api/auth/me' @{ nickname = ('변경-' + [guid]::NewGuid().ToString('N').Substring(0, 8)) } $session.token
    $newPassword = [guid]::NewGuid().ToString('N') + 'a2'
    Request 'PUT' '/api/auth/me/password' @{ currentPassword = $password; newPassword = $newPassword } $session.token
    Request 'DELETE' '/api/auth/me' @{ password = $newPassword } $session.token
    Write-Host '회원 전환·게임 유지·계정 CRUD 시연 완료'
} finally {
    Remove-Item $temporary -Recurse -Force
}
