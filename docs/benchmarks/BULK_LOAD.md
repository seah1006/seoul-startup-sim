# 상가업소 대량 적재 벤치마크

서울 상가업소 CSV(소상공인시장진흥공단, 552,422건)를 `stores` 테이블에 넣는 세 가지 방식을 같은 조건에서 비교했다.

## 측정 환경

- 측정: 2026-11-07 01:43~01:55 KST, 다른 빌드·에이전트 작업 없이 단독 실행
- CPU: AMD Ryzen 5 5600 (6코어 12스레드), RAM 32GB, Windows 11 Pro
- JDK Temurin 25.0.5, Spring Boot 4.1.2, Docker 29.4.0, PostGIS 16-3.5 (로컬 compose)
- 공통 조건: 같은 CSV 스트리밍 파서, 1,000행 배치, 매 측정 전 `TRUNCATE`, 파싱과 GiST/B-tree 인덱스 유지 비용 포함
- 규모별로 웜업 1회 후 3회 측정, **중앙값** 사용 (원시 로그 형식: `BENCH,시각,방식,건수,회차,초,초당건수`)

## 결과

| 방식 | 1만 건 | 10만 건 | 55만 건(전체) | 초당 건수(전체) |
| --- | ---: | ---: | ---: | ---: |
| JPA `saveAll` (batch_size=1000, order_inserts) | 7.29초 | 77.56초 | 미측정* | 약 1,300 (10만 기준) |
| JdbcTemplate `batchUpdate` | 0.66초 | 6.96초 | 38.80초 | 14,238 |
| `COPY` → 스테이징 → `INSERT … ON CONFLICT` | 0.56초 | 6.05초 | **34.94초** | **15,812** |

\* JPA 전체 규모는 10만 건 결과로 볼 때 회당 약 7분, 4회 약 28분이 걸려 측정을 중단했다(지시서 기준: 15분 초과 시 10만까지만).

운영 수집기(`sbiz-stores-csv`, COPY 방식 + 파일 SHA-256 + 스냅샷 비교 포함) 실제 전체 적재: **50.8초**, 552,422건 (CSV 데이터 행 수와 일치).

## 해석

1. **JPA는 JDBC 배치보다 약 11배 느리다.** batch_size를 켜도 엔티티마다 영속성 컨텍스트 관리, 더티 체킹, 객체 생성 비용이 든다. 대량 적재에는 JPA를 쓰지 않고, 게임 도메인처럼 행 수가 적고 객체 관계가 중요한 곳에만 JPA를 쓴다.
2. **COPY는 JDBC 배치보다 약 10% 빠른 데 그쳤다.** 현재 구현은 1,000행마다 스테이징 테이블을 만들고 `INSERT … SELECT`로 옮긴 뒤 지운다. 배치마다 붙는 고정 비용 때문에 COPY 본래의 장점(한 번의 스트림)을 살리지 못한 것으로 본다. → 개선 과제: 파일 전체를 한 번의 COPY 스트림으로 스테이징에 넣고 한 번에 반영(측정 후 이 문서 갱신).
3. 운영 방식으로 COPY를 고른 이유: 현재도 가장 빠르고, 스테이징을 거치므로 본 테이블 반영을 한 번의 `ON CONFLICT` 집합 연산과 스냅샷 비교(NEW/CLOSED/CHANGED)로 처리할 수 있다.

## 반경 조회 성능

망원동 근처 (126.905, 37.556) 반경 300m, 같은 소분류(I21201) 업소 수:

- 실행 시간 **7.7ms** (전체 55만 건, `ANALYZE` 후)
- 실행 계획: `stores_geom_idx`(GiST, bbox 후보 2,130건)와 `stores_small_code_idx`(B-tree)를 `BitmapAnd`로 결합 → 151건 → `ST_DWithin`(geography, 정확한 미터 거리)로 81건
- `geom::geography`만으로 거리를 비교하면 GiST를 못 쓰므로, `geom && ST_Expand(...)`로 인덱스 후보를 먼저 거르는 방식을 쓴다.

## 재실행 방법

```powershell
docker compose up -d
cd backend
.\gradlew.bat benchmark '-Dsbiz.csv=C:\Users\my\dev\seoul-startup-sim\data\raw\sbiz\seoul.csv' --offline --rerun-tasks
```

주의: `benchmark`는 `stores`, `store_snapshot_diffs`를 TRUNCATE한다. 운영 DB에서 실행하지 않는다.

## TASK-004D 재측정 (2026-11-18 19:04~19:07 KST)

전용 compose `sss-004d` DB 5436에서 파일 전체 PipedReader(64KiB) → 단일 COPY → 단일 INSERT SELECT를 측정했다. 웜업 1회 제외 3회 중앙값. 다른 에이전트 및 로컬 Gradle 빌드·테스트 동시 부하가 있었으므로 기존 단독 실행 값과 공정한 속도 비교는 아니다.

| 방식 | 1만 건 | 10만 건 | 552,422건 | 전체 초당 건수 |
| --- | ---: | ---: | ---: | ---: |
| StreamingCopyStoreBulkLoader | 0.413초 | 6.614초 | 35.650초 | 15,496 |

전체 원시 측정: 웜업 34.425초, 본측정 32.447 / 37.371 / 35.650초. 10만: 4.900 / 6.614 / 6.732초. JPA 전체는 측정하지 않았다. 기존 COPY/JDBC의 동일 시점 재측정은 미실시. 벤치마크 도중 별도 clean 작업이 결과 바이너리 파일을 지워 Gradle 보고서 생성은 실패했지만 위 12개 원시 BENCH 행은 모두 로그에 남았다. 이 수치는 이후 추가한 ANALYZE·스냅샷 DELETE 비용은 포함하지 않는다.

운영 수집기는 속도 우위 확정이 아니라 조회 잠금 제거를 위해 streaming으로 전환했다. 이전 TRUNCATE의 ACCESS EXCLUSIVE 잠금은 적재 전체 약 50.8초 동안 유지될 수 있었으나, 변경 후에는 TRUNCATE 없이 COPY 완료 후 upsert + 사라진 점포 DELETE를 수행한다. DML 트랜잭션이 길어도 MVCC SELECT를 막는 ACCESS EXCLUSIVE 잠금은 취득하지 않는다. 실제 전체 스냅샷 잠금 시간 실측은 미완료이며, 별도 연결의 반경 조회를 1.5초 statement_timeout으로 검사하는 통합 테스트를 추가했다.

재실행: `gradlew.bat benchmark --tests '*.streaming' '-Dsbiz.csv=C:\Users\my\dev\seoul-startup-sim\data\raw\sbiz\seoul.csv' --offline --max-workers=2`. 같은 worktree에서 clean 작업과 병행하지 않는다.
