# TASK-005B: RECORD 엔딩 인증글 + 공유 페이지 연결 (백엔드)

- 브랜치: `task/TASK-005B`
- 담당: Codex
- **착수 게이트**: TASK-005A 병합 + 엔딩·랭킹 작업(계약 3장 `GET /api/games/{gameId}/summary`, 엔딩 원본(`game_records` 등), `/share/{gameId}`)이 main에 병합된 뒤. 착수 전에 메인 에이전트가 summary 원본 테이블·컬럼과 공유 컨트롤러의 실제 위치를 확인해 이 지시서에 적는다.
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` 7장·19장, `docs/api/API_CONTRACT.md` 3장(엔딩·공유)·4장(RECORD, 판정 순서와 안전 규칙)
- **Flyway 추가 없음** (V11에 RECORD 컬럼, 부분 유일 인덱스, 트리거가 이미 있다)
- game 패키지 수정 금지. 예외(리뷰 I5): 공유 컨트롤러가 game 패키지에 있으면 그 컨트롤러에 "공개 인증글 링크 조회 위임" 한 곳만 추가 허용. 요약·소유·엔딩 쓰기 로직은 건드리지 않는다.

## 할 것
1. `BoardRecordReadRepository` (읽기 전용): 게임 존재·소유자·status, 그리고 3장 summary와 **같은 원본**에서 스냅샷 9개 필드(endingType, serviceCode/Name, areaCode/Name, shopName, ownerName, survivedQuarters, totalProfit)를 읽는다. 원본은 코드로 확인하고 보고(추측 금지). 원본이 없으면 글을 만들지 않고 500 `INTERNAL_ERROR`.
2. `BoardRecordService`
   - RECORD 작성: 요청은 `gameId`, 제목, 본문만. 판정: 401 → 400 → 404(게임 없음 **또는 남의 게임**, 존재 노출 금지) → 409 `RECORD_GAME_NOT_ENDED` → 스냅샷 복사 → 삽입.
   - 제약 이름 `ux_posts_record_game` 위반 → 409 `RECORD_ALREADY_POSTED`. 트리거(23514)는 서비스 검증을 우회한 경우에만 나오므로 별도 코드로 바꾸지 말고 500으로 남겨 조사(재조회가 필요하면 실패 트랜잭션 롤백 뒤 새 트랜잭션에서).
   - 005A의 "RECORD 작성 400" 차단 해제.
   - 수정 시 RECORD ↔ 다른 카테고리 전환, `gameId` 변경은 400.
3. `GET /api/board/records/{gameId}` (공개, 204/200, `Cache-Control: no-store`)
4. `/share/{gameId}`에 공개 인증글 섹션(제목, 댓글 수, `/board/posts/{postId}` 링크). 없으면 생략. 공유 페이지는 로그인·세션 없이 공개. `th:text`/`th:content`만 사용.
5. 시드: 005A 시드의 SQL 직접 삽입 RECORD 3건은 유지하고, **API로 작성하는** 시연용 ENDED 게임 1개(RECORD 미작성)를 추가.

## 테스트
- RECORD 성공: summary 응답에 있는 7개 필드(endingType, serviceName, areaName, shopName, ownerName, survivedQuarters, totalProfit)는 summary API 응답과 비교, `serviceCode`·`areaCode`는 같은 엔딩 원본 행과 직접 비교(리뷰 I5)
- 404(없는 게임), 404(남의 게임), 409(진행 중), 같은 게임 두 번째 409, 삭제 후 재작성 201, HIDDEN이면 재작성 409
- 스냅샷 위조 시도: 요청에 `totalProfit` 등을 넣어도 무시
- 동시 RECORD 작성 2건 병렬(fixture 커밋, worker별 트랜잭션) → 201 1건, 409 1건, 활성 행 1개
- 트리거 직접 검증: JdbcTemplate로 남의 게임 RECORD INSERT → 예외
- records 조회 204/200, 공유 페이지 HTML에 링크, 숨김 후 링크 사라짐

## 완료 조건
1. `Push-Location backend; .\gradlew.bat clean check --offline; Pop-Location` 통과 (테스트 수)
2. 스냅샷 원본(테이블·컬럼)과 summary 응답 필드 대응표
3. `curl.exe -i` 시연: summary → RECORD 작성 → 목록 RECORD 필터(serviceCode, areaCode) → records 조회 → 공유 페이지 HTML의 링크
