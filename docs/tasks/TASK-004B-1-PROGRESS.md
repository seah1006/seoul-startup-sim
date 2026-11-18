# TASK-004B 파트 1 진행

## 1차 실행 (2026-11-18)

파트 1 전체 완료 아님. V8·시드 생성과 독립 난수 스트림까지 구현했다.

### 구현·커밋
- `814ffed`: 규범 V8 스키마 원본을 그대로 앞에 두고, CONTENT_DRAFT JSON 블록을 SQL INSERT로 자동 변환해 추가했다.
- 생성 스크립트: `pwsh -File docs/tasks/drafts-004b/generate-events.ps1`. `-Check`는 재생성 결과와 현재 V8이 일치하는지 검사한다. LF/CRLF 차이는 무시한다.
- PostgreSQL/PostGIS Testcontainers 위 Flyway V1~V8 적용 및 정의 수·선택지 수 제약 확인 테스트 추가.
- 기존 004A 골든 테스트에는 허용된 TRUNCATE 한 줄만 추가했다. 골든 JSON과 테스트 본문은 수정하지 않았다.
- `3814c1b`: ENGINE 1장 그대로 FNV-1a(UTF-8), SplitMix64 혼합, 목적 enum 9개 기반 `EventRandom` 구현. 기존 매출 난수·계산기는 수정하지 않았다.
- 기존 정의 판정을 새 정의·평가 순서 변경이 흔들지 않는지, 매출 스트림과 분리되는지, FNV 기준 벡터, 1만 회 확률 분포를 검사한다.

### 검증
- 생성기 실행 및 `-Check`: 통과. 전환점 **12**, 선택지 **28**, 반응 **36**, 순간 **12**.
- 관련 테스트: `gradlew.bat test --offline --tests '*EventSeedIntegrationTest' --tests '*EventRandomTest' --tests '*GameIntegrationTest' --tests '*QuarterCalculatorTest'` 성공, **22개 / 실패 0**.
- 004A `GameIntegrationTest.snapshot`의 고정 시드 4분기 JSON 바이트 비교 통과.
- 전체 `gradlew.bat clean check --offline`: **BUILD SUCCESSFUL**, 1분 44초, **60개 / 실패 0 / 오류 0 / 건너뜀 0**.
- compose 전용 DB 포트는 **5438** 확인. 메인 DB 5433 미사용. 마이그레이션 검증은 격리 Testcontainers DB에서 수행했다.

정의 수 확인 쿼리(시드 통합 테스트가 동일 조회를 실행하고 예상 수를 단언):
```sql
SELECT (SELECT count(*) FROM turning_points) AS turning_points,
       (SELECT count(*) FROM turning_point_options) AS options,
       (SELECT count(*) FROM reaction_templates) AS reactions,
       (SELECT count(*) FROM moment_templates) AS moments;
-- 12 | 28 | 36 | 12
```

### 다음 실행의 정확한 잔여
1. 조건·확률·효과 DSL 파서/평가기/검증기: 화이트리스트, 깊이·노드 상한, 범위, 오류 경로, 저장·로드 시 검증. 시드 전체 12+36+12 정의를 검증기로 로드하는 회귀 테스트.
2. `QuarterCalculator.Input`에 `List<Modifier>` 한 필드 추가, 빈 목록은 기존 산술 경로 보존, ADD→MULTIPLY·용량 상한·원가율 clamp·임대료 보정 및 관련 테스트. MarketDataRepository 변경 없이 AdvanceService에서 입력 보정을 연결할 방법을 적용한다.
3. EventEngine/저장소·효과 적용·전환점 발생 및 payload/rev 스냅샷. 엔딩 우선, 쿨다운, 한 분기 하나, 대기 중 advance 차단, GameState.pendingTurningPoint 연동.
4. 전환점 조회/선택 API 및 endpoint별 멱등 처리·동시 선택 직렬화. 선택은 version만 증가시키고 turn은 유지한다.
5. 반응 2~3개, 순간(STAT ±2·MODIFIER 1~2분기만, 현금 금지), 가상 경쟁점 변화와 반응 문구, 결과/집계 JSON 저장.
6. `/admin/turning-points`, `/admin/reactions`, `/admin/moments` 목록·토글·JSON 수정·검증 오류 경로·CSRF 테스트.
7. 이벤트 on 새 8분기 골든, 성공/실패 확률 경계·보정 만료·원장·shop_count·엔딩/멱등/동시성 통합 테스트 및 **8분기 데모 표**(현재 미구현).
8. 최종 전체 clean check 및 완료 조건 재검증.

MarketDataRepository와 GameService 버전 고정 코드, 다른 Flyway 버전, 파트 2는 수정하지 않았다. push 하지 않았다.
