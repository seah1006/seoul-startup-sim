# TASK-002B 구현 보고

## 구현
- Resilience4j 2.3.0 core 모듈 조합: Boot 4 starter 호환성 의존을 피하고 명시적으로 구성.
- 기본 RateLimiter 5회/초, Retry 최대 시도 3회(첫 호출 포함), 대기 500ms→1s. 요구서의 0.5→1→2s를 모두 사용하려면 max-attempts=4로 설정한다.
- CircuitBreaker 최근 5회가 모두 재시도 가능 장애이면 30초 열림. 개별 시도에 적용하여 긴 장애에서 요청을 빠르게 중단. 인증 오류는 차단기 실패율에서 제외.
- 연결 3초, 요청 응답 제한 10초. 모든 정책은 app.external.seoul 아래 설정.
- V3 ACTIVE 인덱스를 source+coalesce(period,'')로 변경. 기간 없는 기존 수집기 유일성 유지.
- 매출은 총액·건수, 주중/주말, 아침/점심/저녁, 남녀 금액만 펼침. 수요 규모·영업 시간·성별 상대 비교에 필요한 최소 필드이며 성별 누락 법인 매출은 원본으로 확인 가능. 인구는 상대 비교만 허용.
- IngestService 변경 없음. 분기 적재와 버전 전환은 단일 트랜잭션. 원본은 기존 REQUIRES_NEW로 보존.
- samples 실제 응답을 잘라 적재/멱등/원본/버전 테스트에 사용.

## 검증
- 최초 clean check: 15개 중 신규 테스트 4개 포함 14개 성공, 기존 마이그레이션 개수 기대값 1이 실패. V1+V3의 2로 수정 후 재검증.
- 실제 API 20262 적재와 20251~20262 백필: 미검증.
- 추가 테스트: 정상 2페이지(1,001행), 중간 페이지 실패 시 기존 데이터/버전 전체 비교, CircuitBreaker 개방 후 HTTP 미호출 및 FAILED 기록, 초당 2건·5페이지(4,001행)의 1.5초 하한 검증을 추가했다. 테스트별 정책 인스턴스를 격리하고 재시도 1ms·차단 2초로 실행 시간을 줄였다.
- bootRun 실행하지 않음. 별도 앱 프로세스 없음.
- sss-002b-db-1은 전용 sss-002b_seoul_startup_sim_db 볼륨 사용. 기존 공유 볼륨은 마운트하지 않음.

## 남은 문제 / 제안
- 실제 API 적재와 백필 검증은 메인 에이전트가 수행한다. 추가 안정성 테스트는 구현 에이전트가 후속 검증한다.
- CLI 네트워크 제한으로 임시 로컬 Maven 저장소를 Gradle init 스크립트로만 참조해 검증. build.gradle은 mavenCentral만 유지하고 임시 파일은 커밋하지 않음.
- 병합 시 기존 IngestIntegrationTest 마이그레이션 기대값을 전체 마이그레이션 수에 맞게 조정 필요.

## 최종 실행 결과
- clean check --offline -I ../.deps.init.gradle: BUILD SUCCESSFUL (34초), 테스트 15개 통과.
- 실제 키를 소스와 테스트 결과 28개 파일에서 검색: 발견 0건.

## 후속 핵심 테스트 검증
- test --tests com.seah.startupsim.SeoulPipelineResilienceTest --offline: 4개 통과, BUILD SUCCESSFUL (30초).
- 마지막 clean check --offline: 19개 통과, BUILD SUCCESSFUL (51초). 임시 Maven 저장소 없이 공유 Gradle 캐시로 검증.
- 5페이지 속도 제한 테스트 전체 소요 2.706초(하한 1.5초 충족), 회로 차단 테스트 전체 소요 0.050초. 개별 XML은 backend/build/test-results/test 참조.

## 실제 API 숫자 파싱 회귀 수정
- 원인: QuarterlyCollector가 숫자 노드도 asText()→Long.valueOf()로 변환했다. HTTP 응답의 실수 노드는 지수 표기 또는 .0 문자열이 되어 실패했다. 전용 DB raw_payloads에서 점포 156.0, 인구 433442.0, 매출 일부 6239093.0을 확인했다. 샘플의 정수 토큰만 사용한 기존 테스트는 이 차이를 놓쳤다.
- 수정: API mapper의 실수는 BigDecimal로 보존하고, 숫자 노드는 decimalValue(), 문자열은 new BigDecimal() 후 longValueExact()로 검증한다. 정수 %인 개폐업률도 동일 정책이며 소수부가 0이 아니거나 bigint 범위를 넘으면 명시적으로 거절한다.
- 운영 경로 회귀: RANDOM_PORT 관리자 HTTP→백필→Spring 등록 수집기로 세 샘플을 적재. 샘플의 response 원문을 추출해 숫자 토큰은 그대로 보존하고 list_total_count만 샘플 행 수로 조정한다. 별도로 실수·지수·문자열 숫자 및 767000000000 매출을 검증한다.
- 실패 runId: IngestService에 시작 ID 콜백 오버로드를 추가해 백필이 자신이 시작한 실행 ID를 보존한다. 동시 실행 등 실행 자체가 시작되지 않은 실패만 null일 수 있다. 기존 run(source,period) 동작과 예외는 유지한다.
- 실패 시 raw_payloads가 남는 것은 의도된 동작이다. 기존 REQUIRES_NEW 트랜잭션이 분기 적재 롤백과 별도로 원본을 보존해 장애 분석·재처리를 지원한다.
- 후속 회귀 클래스 7개 통과(32초). 마지막 clean check --offline: BUILD SUCCESSFUL (45초), 전체 22개 통과.
