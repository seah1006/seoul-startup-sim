# TASK-004E 최종 검증·마이그레이션 리허설

- 실행일: 2026-11-18 (Asia/Seoul)
- 브랜치: task/TASK-004E
- 검증 코드: cf901f1
- 메인 DB는 읽기 전용 `pg_dump -Fc`만 실행했다. 메인 DB에 복원·마이그레이션·쓰기 쿼리를 실행하지 않았다.
- 복원 및 앱 연결 대상은 전용 컨테이너 `sss-004e-db-1`, 호스트 5440이다. 앱은 8088, 자동 수집은 명시적으로 비활성화했다.

## 1. 덤프·복원

1. `.env`의 DB_USERNAME을 출력 없이 읽어 메인 컨테이너 `seoul-startup-sim-db-1`에서 `pg_dump -U <계정> -Fc seoul_startup_sim` 실행.
2. 저장소 밖 TEMP에 덤프 저장 후 `docker cp`로 전용 컨테이너 `/tmp/rehearsal.dump`에 복사.
3. 전용 컨테이너 안에서 `pg_restore -U <계정> -d seoul_startup_sim --clean --if-exists --no-owner --no-privileges /tmp/rehearsal.dump` 실행.
4. 복원 직후 Flyway 최신 버전은 7이었다.
5. 호스트·전용 컨테이너 덤프 파일은 finally에서 삭제했고, 최종 파일 부재를 확인했다.

- 덤프: 58.68초, 223,472,072 bytes
- 전용 DB 복원: 84.14초

## 2. Flyway 적용 및 행 수 보존

전용 DB URL이 5440인지 출력 없이 확인한 뒤 다음 명령으로 부팅했다.

```powershell
cd backend
.\gradlew.bat bootRun --offline --args="--server.port=8088 --app.ingest.schedule.enabled=false"
```

- Flyway 로그: `Successfully applied 1 migration ... now at version v7.1 (execution time 00:06.174s)`
- `flyway_schema_history`: version=7.1, success=true, execution_time=6174ms
- 앱 기동: 14.275초

| 테이블 | 적용 전 | 적용 후 | ACTIVE 뷰 |
| --- | ---: | ---: | ---: |
| area_sales_q | 481,462 | 481,462 | 481,462 |
| area_stores_q | 1,680,756 | 1,680,756 | 1,680,756 |
| area_floating_pop_q | 36,281 | 36,281 | 36,281 |
| area_worker_pop_q | 36,027 | 36,027 | 36,027 |

4개 테이블 모두 행 수가 정확히 일치했다. 이 덤프의 분기 행은 모두 현재 ACTIVE 버전이어서 ACTIVE 뷰 행 수도 동일하다. 재수집으로 이전 버전이 READY가 된 경우의 뷰 분리는 수집 통합 테스트로 별도 검증한다.

`game_data_pins.version_id` 제약은 실제 DB에서 다음과 같이 확인했다.

```sql
FOREIGN KEY (version_id) REFERENCES data_versions(id) ON DELETE RESTRICT
```

## 3. 전체 테스트

```powershell
cd backend
.\gradlew.bat clean check --offline
```

- BUILD SUCCESSFUL in 2m 32s
- 14개 테스트 클래스, 68개 테스트
- 실패 0, 오류 0, 스킵 0
- benchmark 태그는 build.gradle의 일반 check 설정대로 제외된다.
- 포함된 회귀: 2분기 후 동일 분기 재수집과 게임 JSON 바이트 동일성/카탈로그 갱신, 창업 후 점포·상권 재적재, 고정 버전 정리 보호, Hikari 최대 2개에서 느린 API 중 조회, AreaMeta 공통 재시도, 스케줄 신규/빈 응답, 인증 경로 IP 제한·비밀 마스킹.
