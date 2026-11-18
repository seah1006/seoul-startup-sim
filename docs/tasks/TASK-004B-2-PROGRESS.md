# TASK-004B 파트 2 진행 (엔딩·기록·랭킹·공유, V9)

## 구현 위치
| 항목 | 위치 |
| --- | --- |
| V9 | `db/migration/V9__endings_ranking.sql` (`drafts-004b/V9__endings_ranking.sql`과 바이트 동일) |
| 엔딩 기록 | `game.GameRecordRepository.insert` ← `AdvanceService.finishQuarter`(CLOSED·LONG_RUNNING), `EndingService.sell`(SOLD). 같은 트랜잭션, PK로 중복 불가 |
| 매각 | `POST /api/games/{id}/sell` → `game.EndingController` / `EndingService.sell` |
| 요약 | `GET /api/games/{id}/summary` → `EndingService.summary` → `game_records` |
| 랭킹 배치 | `ranking.RankingBatch.refresh(Instant)` (advisory xact lock `RankingBatch.LOCK_KEY`), `ranking.RankingScheduler`(cron `0 0,30 * * * *` Asia/Seoul + `ApplicationReadyEvent` 1회) |
| 랭킹 API | `GET /api/rankings` → `ranking.RankingController` / `RankingService` / `RankingRepository` |
| 관리자 | `GET /admin/rankings`, `POST /admin/rankings/refresh` → `web.AdminRankingController`, `templates/admin/rankings.html` |
| 공유 | `GET /share/{gameId}` → `web.ShareController` / `web.ShareRepository`, `templates/share/game.html`, `share/not-found.html`, OG 이미지 `static/og/share-default.jpg` |

## 결정
- **닉네임 스냅샷 vs 탈퇴 표시**: `game_records.nickname`은 엔딩 시점 값을 그대로 둔다(수정하지 않음). 표시하는 쿼리(랭킹, 공유)에서 `users`를 조인해 `deleted_at IS NOT NULL`이면 `'탈퇴한 사장님'`으로 덮어쓴다(`GameRecordRepository.DISPLAY_NICKNAME`).
  - 근거: 탈퇴 처리(auth 패키지)가 game_records까지 고쳐 쓰지 않아도 되고, 과거·미래 탈퇴 모두 즉시 반영된다. `ranking_snapshots`는 `game_id`만 갖고 있어 배치 재실행 없이도 바로 반영된다. 탈퇴하지 않은 사용자의 닉네임 변경은 엔딩 당시 이름을 유지한다(기록의 의미).
- **survived_quarters / total_profit / best_quarter_profit**: `turn_results`의 행 수 / `profit` 합 / `profit` 최댓값(행이 없으면 0). 매각 대금과 전환점 현금 효과는 누적 순이익에 넣지 않는다(매각 대금은 `sale_value`에 따로 기록).
- **매각**: 판정 순서 키 검증 → 행 잠금·소유권(404/403) → 같은 키 캐시 → 버전 409 → 종료 409 `GAME_ENDED` → 전환점 대기 409 → 영업 분기 수 400 → 정산. 가치 = `max(0, 최근 4분기 평균 순이익) × sell.quarters`, 원 단위 HALF_UP. 0원이어도 `SALE` 원장 0원 1행을 남긴다. `sell.quarters`·`sell.minQuarters`는 게임 생성 시 설정 스냅샷(`games.settings`)에서 읽고, V9 이전 게임은 기본값 4. 원장 `turn`은 마지막 결산 분기(`current_turn - 1`).
- **랭킹 상위 100**: `RANK() <= 100`이라 100위 동점자는 모두 저장된다. API `limit` 최대 100.
- **refreshedAt**: 그 날짜 스냅샷의 `max(refreshed_at)`. 기록이 없는 날은 행이 없으므로 이 인스턴스의 마지막 배치가 그 날짜를 갱신했으면 그 시각, 아니면 null.
- **테스트의 스케줄 끄기**: `src/test/resources/application.properties`에 `app.ranking.schedule.enabled=false`(기본 true). 테스트는 `RankingBatch.refresh(Instant)`를 직접 호출한다.
- **공유 경로 보안**: `/share/**`와 `/og/**`는 `ApiSecurityConfig.publicSecurity`(Order 3, `anyRequest().permitAll()`)가 처리한다. 체인 변경 없음, 테스트에서 무인증 200/404 확인.
- OG 이미지는 `/share/{gameId}`와 경로가 겹치지 않도록 `/og/share-default.jpg`에 둔다.
- `GameEndedEvent`는 advance와 sell 모두 발행한다. 커밋 후 리스너는 추가하지 않았다(랭킹은 30분 배치·관리자 갱신).

## TASK-005B용 대응표: `game_records` ↔ RECORD 스냅샷 9필드
| RECORD 스냅샷 (`posts` 컬럼 / 응답 필드) | `game_records` 컬럼 | summary 응답 필드 |
| --- | --- | --- |
| endingType | `ending_type` | `endingType` |
| serviceCode | `service_code` | (없음, 원본 행 직접 비교) |
| serviceName | `service_name` | `serviceName` |
| areaCode | `area_code` | (없음, 원본 행 직접 비교) |
| areaName | `area_name` | `areaName` |
| shopName | `shop_name` | `shopName` |
| ownerName | `owner_name` | `ownerName` |
| survivedQuarters | `survived_quarters` | `survivedQuarters` |
| totalProfit | `total_profit` | `totalProfit` |

- 게임 존재·소유·종료 판정은 `games.user_id`, `games.status`(ENDED)로 한다. `game_records.user_id`도 같은 값이다.
- 공유 컨트롤러 위치: `com.seah.startupsim.web.ShareController` (game 패키지 밖). 인증글 섹션 자리는 `templates/share/game.html`의 `TASK-005B` 주석.

## 검증
- `gradlew.bat clean check --offline`: BUILD SUCCESSFUL (2분 57초), **126개 / 실패 0 / 오류 0 / 건너뜀 0**.
- 004A 고정 시드 골든(`GameIntegrationTest.snapshot`, `four-quarters.json`)과 004B-1 8분기 이벤트 골든(`EventFlowIntegrationTest.eightQuarterGolden`, `events-on-eight-quarters.json`) 통과. 골든 파일·두 테스트 파일 수정 없음.
- 새 테스트: `EndingIntegrationTest` 10, `RankingIntegrationTest` 7, `ShareWebIntegrationTest` 3.

## 데모 (2026-11-18, DB 5442, 앱 8090)
`docs/tasks/TASK-004B-2-demo.ps1` (데모 상권 DEMO_A·DEMO_B, 6분기 픽스처를 SQL로 적재한 뒤 실행, 이벤트 시드 켜짐)

| 게임 | 엔딩 | 생존 | 누적 순이익 | 비고 |
| --- | --- | ---: | ---: | --- |
| 1 | LONG_RUNNING | 6 | 400,156,746 | 전환점 3회(DELIVERY_APP, RENT_HIKE, CORPORATE_CATERING) |
| 2 | SOLD | 4 | 130,977,722 | 매각 전 잔액 178,977,722 → 후 309,955,444 (매각가 130,977,722) |
| 3 | CLOSED | 2 | -174,159,520 | 분기 마케팅비 2억 |

- 앱 시작 직후 배치 1회 실행 로그 확인(`랭킹을 갱신했습니다: [2026-11-18]`).
- 관리자 로그인 → `POST /admin/rankings/refresh` 302 → 화면 마지막 갱신 시각 표시.
- `curl.exe -i /api/rankings?kind=ALL` 200: rank 1·2·3 = 게임 1·2·3, `gameId` 포함, `refreshedAt` `+09:00`.
- `curl.exe -i /api/rankings?kind=INDUSTRY&key=CS100010&limit=2` 200: 2건. `kind=AREA` key 누락 400 `VALIDATION_FAILED`.
- `curl.exe -i /api/games/2/summary` 200: `{"gameId":2,"endingType":"SOLD",…,"shareUrl":"/share/2"}`.
- `curl.exe -i /share/1` 200 HTML, OG `og:title`=`데모 장수커피 · 장수 가게`, `og:description`=`6분기 생존 · 누적 순이익 400,156,746원`. `/share/999999` 404 HTML.
