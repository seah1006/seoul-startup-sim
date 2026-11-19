# TASK-008: 공개 배포 준비 (Oracle Cloud Always Free E2.1.Micro, 메모리 1GB)

- 브랜치: `task/TASK-008` (worktree `C:\Users\my\dev\sss-008`)
- 담당: Codex
- 결정: 사용자가 "무료/저가 클라우드"를 골랐다. 기존 Oracle 계정(홈 리전 춘천, Arm 무료 할당 0)이라 **AMD `VM.Standard.E2.1.Micro`(1/8 OCPU, 메모리 1GB)** 두 번째 무료 인스턴스에 올린다. 첫 번째는 다른 프로젝트(WELF)가 쓰고 있다.
- 참고: 같은 PC의 WELF 배포 방식(`C:\Users\my\Desktop\프로젝트\WELF\docs\deploy.md`, `docker\Caddyfile`, `docker-compose.serve.yml`, `scripts\deploy_server.ps1`, `scripts\server_setup.sh`) — 읽기만 하고 수정 금지. 구조를 그대로 본떠도 된다.

## 원칙
- **서버에서 빌드하지 않는다.** 1GB에서 Gradle·npm 빌드는 무리다. PC에서 jar와 프론트 `dist`를 만들어 보내고, 서버는 실행만 한다.
- 비밀 값은 서버 `.env`(권한 600)에만. 저장소에는 `.env.serve.example`만.
- 공개 주소는 `sslip.io`(예: `sss-<IP 대시>.sslip.io`) + Caddy 자동 인증서. 카카오 JS SDK 도메인 등록은 메인이 한다.

## 만들 것
1. `deploy/docker-compose.serve.yml`
   - `db`: `postgis/postgis:16-3.5`, `shared_buffers=64MB`, `max_connections=20`, `work_mem=4MB`, 볼륨 이름 고정 금지, 헬스체크, 메모리 제한(예: 300M)
   - `app`: `eclipse-temurin:25-jre`(또는 alpine 대응 이미지) + 보낸 `app.jar` 마운트, JVM 옵션 `-Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1`, Hikari 최대 커넥션 5, 메모리 제한(예: 520M), 헬스체크 `/api/health/db`. 프로필 `serve`
   - `caddy`: `caddy:2.10`, 80/443, `frontend/dist` 정적 파일 + `/api/*`, `/admin*`, `/share/*`, `/og/*` 등 백엔드 경로만 `app:8080`으로 프록시(나머지는 SPA fallback `index.html`), gzip/zstd, 보안 헤더
2. `backend/src/main/resources/application-serve.yml`: 서버 프로필. 수집 스케줄 꺼짐, 외부 API 키 없어도 기동, 로그 레벨 INFO, `server.forward-headers-strategy=framework`(Caddy 뒤 IP 처리), IP 제한 필터의 trust-proxy를 Caddy 앞단 기준으로 켜는 설정(신뢰 프록시는 같은 도커 네트워크만), 관리자 계정은 `.env`
3. `deploy/Caddyfile`
4. `deploy/server_setup.sh`: Ubuntu 24.04 Minimal용. 시간대 Asia/Seoul, 스왑 2GB, Docker 설치, iptables 80/443 허용(REJECT 규칙 **앞**에, WELF 문서 주의사항), 재부팅 후 유지
5. `deploy/deploy_server.ps1`(PC에서 실행): 백엔드 `bootJar` → 프론트 `npm ci && npm run build`(실제 API 모드, `VITE_USE_MOCK=false`, 카카오 키는 PC `frontend/.env.local`에서 빌드 시 주입) → `scp`로 jar, dist, compose, Caddyfile 전송 → `docker compose up -d`. SSH 키 경로·호스트는 파라미터.
6. `deploy/load_data.ps1`(PC에서 실행): 메인 DB(5433) `pg_dump -Fc`(읽기만) → 서버로 전송 → 서버 DB에 `pg_restore`(처음 한 번). 덤프 크기·소요 시간 출력, 끝나면 덤프 파일 삭제. 앱이 Flyway를 다시 적용하지 않도록 순서(복원 후 앱 기동) 주의.
7. `docs/DEPLOY.md`: 처음 만들 때 순서, 코드 바꿨을 때, 데이터 갱신(분기 업데이트: PC에서 수집 → 덤프 → 서버 복원 또는 앱의 관리자 백필), 확인 항목, 메모리 실측, 주의사항.

## 로컬 리허설 (가장 중요)
- PC에서 1GB 서버를 흉내 낸다: 같은 compose를 `docker compose -p sss-serve-rehearsal` 로 띄우되 **전체 컨테이너 메모리 합 950MB 이하**(각 서비스 `mem_limit`)로, Caddy는 `localhost:8443`(자체 인증서 또는 `tls internal`)로.
- 메인 DB 덤프를 리허설 DB에 복원 → 앱 기동 → 주요 API(`/api/health/db`, `/api/areas?bbox=…`, `/api/areas/{code}/report`, 게임 생성·창업·분기 진행, 랭킹, 게시판 목록, `/share/{id}`, `/admin/login`) 응답 확인. 각 컨테이너 메모리 사용량(`docker stats --no-stream`)과 첫 응답 시간·분기 진행 응답 시간 기록.
- 메모리가 부족하면 JVM 옵션·Postgres 설정을 조정하고 근거를 DEPLOY.md에.
- 리허설이 끝나면 리허설 컨테이너·볼륨 삭제(메인 DB 5433, WELF 컨테이너는 절대 건드리지 마라).

## 완료 조건
- 리허설 결과 표(메모리, 응답 시간, API별 상태 코드)를 `docs/DEPLOY.md`에
- `gradlew.bat clean check --offline` 통과(설정 추가로 기존 테스트가 깨지지 않는지), 프론트 빌드 통과
- 커밋은 의미 단위, push 금지
