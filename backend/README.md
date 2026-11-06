# 백엔드
Java 25(JAVA_HOME 지정)와 Docker가 필요합니다.
루트 `.env`에 DB_URL, DB_USERNAME, DB_PASSWORD를 설정하세요.

```powershell
# 저장소 루트
 docker compose up -d
 cd backend
 .\gradlew.bat bootRun
 # 다른 터미널
 curl.exe http://localhost:8081/api/health/db
 .\gradlew.bat clean check
```

backend에서 실행하면 루트 .env를 자동 로딩합니다. 테스트는 compose와 별개인 PostGIS 16-3.5 컨테이너를 사용합니다.
Collector를 Spring Bean으로 등록하고 IngestService.run(source, period)를 호출합니다. 수집기 예외는 FAILED를 별도 트랜잭션으로 남기고 재발생합니다. 실패 메시지는 설정된 키와 URL 쿼리/서울 API 경로 키를 마스킹한 후 최대 1,000자로 기록합니다. 원본도 독립 트랜잭션으로 저장합니다. RUNNING 부분 유니크 인덱스가 다중 인스턴스에서도 중복 실행을 거절합니다. 앱 시작 시 app.ingest.stale-after(기본 2h)보다 오래된 RUNNING은 FAILED로 자동 정리합니다. 정상 수집의 최대 소요 시간보다 크게 설정하세요. 여러 인스턴스에서는 이 기준보다 오래 실행 중인 다른 인스턴스의 수집도 정리될 수 있습니다.
포트: DB 5433, 앱 8081 (다른 로컬 프로젝트와 충돌 방지).
