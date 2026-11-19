# 공개 배포 (Oracle E2.1.Micro 1GB)

서버에서는 빌드하지 않는다. PC Java 25/Node에서 jar/dist를 만들어 전송한다. 실제 Oracle 생성·접속은 별도 작업이다.

## 최초 설치
1. Ubuntu 24.04 Minimal, AMD E2.1.Micro, 공개 IPv4/SSH 키. OCI 보안 목록 TCP 80/443 허용.
2. `deploy/server_setup.sh` 전송 후 `bash server_setup.sh`. 서울 시간대/2GB swap/Docker/iptables를 설치한다. 재접속한다.
3. 서버 `~/sss`를 만들고 `deploy/.env.serve.example`을 `.env`로 복사해 강한 DB/관리자 비밀번호, `SITE_ADDRESS=sss-<IP 대시>.sslip.io`를 채운다. `chmod 600 .env`. `.env`는 전송/커밋하지 않는다.
4. PC `frontend/.env.local`에 카카오 JS 키 설정. 카카오 플랫폼에 HTTPS 공개 도메인 등록. 키는 빌드 시 브라우저 번들에 포함되는 공개 JS 키만 사용한다.
5. `pwsh deploy/deploy_server.ps1 -Server ubuntu@<IP> -Key <SSH키> -StageOnly`: PC bootJar/npm ci/build, 서버 DB만 기동.
6. `pwsh deploy/load_data.ps1 -Server ubuntu@<IP> -Key <SSH키>`: 메인 DB 읽기 pg_dump, 서버 복원, 앱/프록시 시작. **복원 전 앱을 시작하지 않는다.** Flyway 이력도 함께 복원한 뒤 앱이 필요한 새 마이그레이션만 적용한다.
7. 공개 HTTPS, `/api/health/db`, 지도/리포트/게임/게시판/관리자 로그인 확인. Caddy 자동 인증서에 TCP 80/443 및 정상 DNS가 필요하다.

## 코드 변경
같은 deploy_server.ps1을 StageOnly 없이 실행. 서버 .env/DB 볼륨 유지, app/caddy 강제 재생성. 실패하면 기존 jar/dist 버전을 다시 배포한다. 자동 롤백 없음.

## 분기 데이터 갱신
PC에서 수집/검증 후 load_data.ps1 실행. 공개 서버 수집 스케줄은 꺼져 있다. 복원은 서버 전체 데이터를 교체하므로 게임/회원/게시글도 PC 상태로 바뀐다. 운영 사용자 데이터가 생긴 뒤에는 전체 덤프 복원을 사용하지 말고 서버 DB 백업 후 관리자 백필을 사용한다. 백필은 별도 외부 키와 충분한 메모리가 필요하므로 1GB에서 대규모 수집은 권장하지 않는다.

## 메모리/보안
- 제한 DB 300MiB + 앱 520MiB + Caddy 80MiB = **900MiB**. 호스트/커널 여유와 2GB swap 필요. PC의 제한 실험은 Oracle 1/8 OCPU 성능을 재현하지 못한다.
- JVM heap 256MiB/metaspace 128MiB/SerialGC/stack 512KiB/tier1. Postgres shared_buffers 64MB/work_mem 4MB/20연결, Hikari 5연결.
- app/db는 호스트 포트 미노출. DB 내부 네트워크와 Caddy-app edge 네트워크를 분리. Caddy가 X-Forwarded-For를 실제 접속 IP로 덮어쓴다. trust-proxy=true는 이 격리 구조 전용이며 앱 포트를 외부에 열면 안 된다. 같은 Docker 네트워크에 신뢰하지 않는 컨테이너를 붙이지 않는다.
- iptables 허용은 Oracle 기본 REJECT **앞**에 삽입하고 netfilter-persistent로 저장. IPv4 공개 주소 기준이다.
- 초기 덤프에는 계정/게임 등 개인정보가 포함될 수 있다. SSH로만 전송하고 임시 덤프 삭제. 실패 시 서버 `/tmp/sss-data.dump`와 DB 컨테이너 `/tmp/sss-data.dump` 잔여물도 지운다.
- HTTPS `/admin/login` 사용. 외부 API 키 없이 기동 가능. 운영 로그 INFO, 비밀값 출력 금지.

## 로컬 리허설
프로젝트 이름 `sss-serve-rehearsal`만 사용. `SITE_ADDRESS=http://localhost`, HTTP_PORT=8443, HTTPS_PORT=8444로 동일 Compose를 실행한다. HTTP 로컬 프록시 기능 리허설이며 공개 TLS 인증서 발급은 서버에서 별도 확인한다.
PostGIS 이미지 기본 tiger 스키마와 덤프가 중복되므로 `pg_restore --clean --if-exists --no-owner --no-acl --exit-on-error`로 복원한다. 앱을 중지한 상태에서 복원한다.
측정 완료 후 `docker compose -p sss-serve-rehearsal -f deploy/docker-compose.serve.yml down -v`로 리허설만 삭제한다. 메인 DB 5433/WELF는 변경하지 않는다.

실측 결과는 아래에 기록한다.

### 2026-11-20 PC 실측
실데이터 덤프 213.2MiB. 메인 DB는 pg_dump 읽기만 수행. 별도 리허설 DB에 복원 완료. 첫 실행 이후 기능 호출을 이어서 측정했으며 동시부하/Oracle CPU 성능 검증은 아니다.

| 컨테이너 | 실사용 | 제한 |
|---|---:|---:|
| PostGIS | 197.4MiB | 300MiB |
| Java 앱 | 226.7MiB | 520MiB |
| Caddy (내부 TLS 포함) | 18.65MiB | 80MiB |
| 합 | 442.75MiB | 900MiB |

| 요청 | 상태 | 최초 측정 ms |
|---|---:|---:|
| DB health | 200 | 18 |
| bbox 지도 | 200 | 296 |
| 3001491 리포트 | 200 | 89 |
| 게스트 | 201 | 51 |
| 게임 생성 | 201 | 507 |
| 유효 좌표 창업 | 200 | 256 |
| 결정 저장 | 200 | 66 |
| 분기 진행 | 200 | 196 |
| 랭킹 | 200 | 26 |
| 게시판 목록 | 200 | 28 |
| 관리자 로그인 | 200 | 971 |

후속 분기 진행 재측정 52ms. 잘못된 상권 좌표로 창업하면 400으로 거절됨도 확인했다. 영업 1분기 게임의 매각은 400, 미종료 게임 공유는 404(정상 계약). 기존 종료 게임 `/share/5`는 아래 검증 상태 참조. Caddy 임시 `tls internal` 설정으로 https://localhost:8444/api/health/db=200 확인(자체 인증서 검증 생략). 배포 Caddyfile은 자동 공개 인증서 설정으로 복원했다. 기본 앱 컨테이너 healthcheck 명령 exit 0.

PowerShell 배포/복원 스크립트 구문 분석: 각각 0오류. frontend npm ci --offline, VITE_USE_MOCK=false npm run build 통과. 서버 실제 setup/SSH/scp 및 Oracle 인증서 발급은 미검증.

기존 종료 게임 /share/5 TLS 응답: 200.

최종 검증: gradlew.bat clean check bootJar --offline BUILD SUCCESSFUL (4분36초), 219 tests / 0 failures / 0 skipped. 리허설 컨테이너/프로젝트 볼륨 삭제 완료. 메인 DB/WELF 컨테이너 유지 확인은 메인 담당자가 별도 재확인 가능.
