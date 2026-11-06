# 상가 대량 적재 벤치마크

## 재실행

전용 compose DB는 5436, 앱은 8084. 루트 `.env`는 출력하거나 커밋하지 않는다.
`docker compose -f docker-compose.yml -f compose.local.yml -p sss-002c up -d`
`compose.local.yml`은 로컬 전용이며 `seoul_startup_sim_db` 볼륨의 이름을 `sss-002c_db`로 변경한다. 기존 공유 볼륨을 사용하면 안 된다.

```powershell
$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot'
cd backend
.\gradlew.bat benchmark '-Dsbiz.csv=C:\Users\my\dev\seoul-startup-sim\data\raw\sbiz\seoul.csv' --offline --rerun-tasks
```

주의: benchmark는 stores 및 store_snapshot_diffs를 TRUNCATE한다. 운영 데이터베이스에서는 실행하지 않는다. 각 규모/전략마다 웜업 1회 후 3회 측정하며 `BENCH` 로그의 run=1,2,3 중앙값을 사용한다. 파일 파싱과 공간 인덱스 유지 비용을 포함한다. 각 전략은 1,000행 배치, JPA는 batch_size=1000 및 order_inserts 사용. JDBC URL에 reWriteBatchedInserts=true를 별도로 붙여 비교할 수 있다(기본 비교에는 미적용).

## 측정 상태

2026-11-07 01:23 KST, 다른 에이전트 2개 동시 작업 중(동시 부하 있음).
JDK Temurin 25.0.5.8, Docker 29.4.0 (9d7ad9f). CPU/RAM 정보는 CIM 명령이 값을 반환하지 않아 미확인.

| 방식 | 건수 | 중앙 시간 | 초당 건수 | 최대 메모리 |
| --- | ---: | --- | --- | --- |
| JPA saveAll | 1만 / 10만 / 552422 | 미측정 | 미측정 | 미측정 |
| JDBC batch | 1만 / 10만 / 552422 | 미측정 | 미측정 | 미측정 |
| COPY staging | 1만 / 10만 / 552422 | 미측정 | 미측정 | 미측정 |

수치와 가장 빠른 방식은 아직 검증하지 못했다. 운영 수집기는 잠정적으로 COPY를 사용하며 실측 후 확정해야 한다.
COPY는 배치별 고유 UNLOGGED staging을 만들고 finally에서 삭제한다. 자동 커밋 모드의 개별 전략 실행과 동일하게 동작시키기 위해 TEMP ON COMMIT DROP을 사용하지 않았다.
스냅샷 교체 서비스는 트랜잭션 안에서 이전 stores를 TEMP 테이블로 보존하고 전체 교체 및 NEW/CLOSED/CHANGED 집합 비교를 원자적으로 수행한다.
전체 파일 메모리 적재 없이 파서는 1,000행, COPY는 해당 배치 CSV 문자열만 보관한다.

## 검증

전체 적재 건수 및 망원동 공간 질의 EXPLAIN ANALYZE: 미검증. benchmark 마지막 단계가 운영 수집기를 실행하고 count 및 EXPLAIN 결과를 출력한다.
공간 질의는 geom의 GiST bounding box 후보와 geography의 정확한 300m 거리를 함께 사용한다. geom::geography만 쓰면 geom 인덱스를 이용할 수 없기 때문이다.

## 파서 선택

RFC4180 직접 구현. 외부 의존성 다운로드가 제한되고 Commons CSV 캐시가 없는 환경에서 의존성 없이 UTF-8 스트리밍 처리가 가능하다. 따옴표/이중따옴표/쉼표/셀 내 개행/CRLF/빈값을 처리하고 좌표 파싱 실패, NaN/Infinity, 세계 범위 밖 좌표는 failed로 집계하여 건너뛴다. 구조가 깨진 CSV는 실패시켜 잘못된 전체 스냅샷 교체를 막는다.
