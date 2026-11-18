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

## 2차 실행 (2026-11-18)

- `62b21a8`: `EventDsl`의 sealed 조건 AST·확률 공식·효과 7종 record와 `EventDefinitionValidator` 추가.
- 조건: 8개 연산자, all/any/not/빈 객체, 결측 비교 false, 숫자·문자열·boolean 타입, 변수·키·연산자 화이트리스트, 깊이 4·노드 30·배열 20 상한. BigDecimal 비교로 원 단위 정밀도 유지.
- 확률: 기준 능력치 40, 최대 2개 statBonus, per10 범위, clamp, 정수 성공률 반올림.
- 효과: CASH/STAT/MODIFIER/FLAG/SHOP_COUNT/RIVAL_ADD/TEXT 각각 엄격한 키·타입·범위. 순간은 STAT ±2·MODIFIER 최대 2분기·TEXT만 허용.
- 전환점/반응/순간 전체 정의 검증 진입점 제공. code·선택지 수/순서·문구 길이·별점·치환 변수 검증. 위반은 400 INVALID_EXPRESSION과 JSON 경로를 포함한다.
- 실제 Flyway로 적재한 V8 콘텐츠를 DB에서 읽어 **전환점 12·반응 36·순간 12 전체 검증 성공**.
- 검증 명령: `gradlew.bat test --offline --tests '*EventDslTest' --tests '*EventSeedIntegrationTest' --tests '*GameIntegrationTest' --tests '*EventRandomTest'`.
- 결과: **BUILD SUCCESSFUL, 21개 / 실패 0 / 오류 0**, 004A 고정 시드 4분기 골든 포함. 이번 실행의 전체 clean check는 미실행(1차 60개 통과 기록과 구분).
- 실행 시간 제한에 따라 DSL 단계에서 커밋. 1차 잔여 목록의 1번 중 파서·평가기·검증기 및 시드 전수 검증은 완료. **실서비스 정의 저장·로드 호출, 부팅 전수 검사/오류 로깅·사용 시 무효 정의 스킵은 흐름·관리자 단계에서 연결해야 한다.**
- 다음 착수는 **계산기 보정**이며 1차 잔여 2~8번을 순서대로 진행한다.
- MarketDataRepository·GameService·game_data_pins·골든 파일은 이번 실행에서 수정하지 않았다. push 없음.
