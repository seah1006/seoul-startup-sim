# 백엔드
Java 25(JAVA_HOME 지정)와 Docker가 필요합니다.
루트 `.env`에 DB_URL, DB_USERNAME, DB_PASSWORD를 설정하세요.

```powershell
# 저장소 루트
 docker compose up -d
 cd backend
 .\gradlew.bat bootRun
 # 다른 터미널
 curl.exe http://localhost:8080/api/health/db
 .\gradlew.bat clean check
```

backend에서 실행하면 루트 .env를 자동 로딩합니다. 테스트는 compose와 별개인 PostGIS 16-3.5 컨테이너를 사용합니다.
Collector를 Spring Bean으로 등록하고 IngestService.run(source, period)를 호출합니다. 수집기 예외는 FAILED를 별도 트랜잭션으로 남기고 재발생합니다. 실패 메시지는 설정된 키와 URL 쿼리/서울 API 경로 키를 마스킹한 후 최대 1,000자로 기록합니다. 원본도 독립 트랜잭션으로 저장합니다. RUNNING 부분 유니크 인덱스가 다중 인스턴스에서도 중복 실행을 거절합니다. 비정상 종료로 남은 RUNNING은 운영자가 실행 종료를 확인한 후 FAILED로 복구해야 합니다.