param([string]$BaseUrl='http://localhost:8082',[string]$StartQuarter='20231')
$ErrorActionPreference='Stop'
# 토큰은 메모리에서만 사용하며 화면이나 파일에 출력하지 않는다.
$guest=Invoke-RestMethod "$BaseUrl/api/guest" -Method Post
$headers=@{'X-Guest-Token'=$guest.guestToken}
$candidates=Invoke-RestMethod "$BaseUrl/api/owners/candidates?background=OFFICE_WORKER&seed=123" -Headers $headers
$game=Invoke-RestMethod "$BaseUrl/api/games" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{ownerName='김사장';background='OFFICE_WORKER';candidateId=$candidates.candidates[0].candidateId;candidateSeed=123;focusStats=@('marketing','service');startQuarter=$StartQuarter}|ConvertTo-Json)
$areas=Invoke-RestMethod "$BaseUrl/api/areas/search?q=$([uri]::EscapeDataString('망원'))"
$area=$areas | Select-Object -First 1
if(!$area){throw '망원 상권 데이터가 필요합니다.'}
# centroid가 폴리곤 밖이면 카탈로그 지도를 확인하여 내부 좌표로 교체한다.
$game=Invoke-RestMethod "$BaseUrl/api/games/$($game.gameId)/shop" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{areaCode=$area.areaCode;serviceCode='CS100010';lng=$area.centroid.lng;lat=$area.centroid.lat;shopName='나의 망원커피'}|ConvertTo-Json)
for($i=0;$i -lt 4;$i++) {
    $null=Invoke-RestMethod "$BaseUrl/api/games/$($game.gameId)/turns/$($game.currentTurn)/decision" -Method Put -Headers $headers -ContentType 'application/json' -Body (@{priceLevel='NORMAL';openHours=10;staff=2;marketingBudget=3000000;ownerActivity='MENU_DEV'}|ConvertTo-Json)
    $headers['Idempotency-Key']=[guid]::NewGuid().ToString()
    $response=Invoke-RestMethod "$BaseUrl/api/games/$($game.gameId)/advance" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{expectedVersion=$game.version}|ConvertTo-Json)
    $game=$response.game
    $response.result | Select-Object turn,quarter,revenue,profit,cashAfter
}
