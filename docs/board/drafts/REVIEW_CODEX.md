# 게시판 구현 가능성 리뷰 (Codex)

범위: TASK-005A/B, V11 확정 SQL, API 계약 4장, 설계 19장 및 현재 백엔드/프론트 구현. 경로를 줄인 Java 파일은 `backend/src/main/java/com/seah/startupsim/`, 테스트 파일은 `backend/src/test/java/com/seah/startupsim/` 기준이다.

## 차단

### C1. 401 어댑터가 필요하지만 005A 완료 조건은 그 의존성을 금지한다

- 근거: `game/GuestAuth.java:22–26`은 누락·무효에 `GameException`을 던진다. `game/GameExceptionHandler.java:15–22`는 게임 패키지 한정이라 게시판 호출에서 적용되지 않으며 `common/ApiExceptionHandler.java:67–79`가 일반 예외를 500으로 처리한다. `GameException.java:4–10`의 상태/코드 필드는 package-private이다. `docs/api/API_CONTRACT.md:323`은 이를 이미 알고 `BoardGuestAuth`의 `GameException` 변환을 명시하지만 `docs/tasks/TASK-005A.md:7,21,27,47`은 `GuestAuth` import 외 의존성을 금지한다. 또한 필수 헤더를 프레임워크에 맡기면 `MissingRequestHeaderException`이 공통 400 목록에 없어 500으로 갈 수 있다(`ApiExceptionHandler.java:42–47,67–73`).
- 수정 문구: “`BoardGuestAuth` 한 클래스에만 기존 `game.GuestAuth`와 `game.GameException` import를 허용한다. `resolve()` 호출 한 줄의 `GameException`만 잡아 고정 메시지의 보드 401 `UNAUTHORIZED`로 변환한다(상태/코드 필드에 접근하지 않음). 필수/선택 모두 `@RequestHeader(name="X-Guest-Token", required=false)`로 바인딩하되 선택은 헤더가 아예 없을 때만 위임하지 않는다. `@RestControllerAdvice(assignableTypes={게시판 REST 컨트롤러들})`에 `@Order(Ordered.HIGHEST_PRECEDENCE)`를 적용하고 `BoardException`만 잡아 공통 `ApiError`를 생성한다. 바인딩 오류는 기존 공통 advice에 그대로 넘기고 관리자 HTML 컨트롤러는 범위에서 제외한다. 완료 조건은 두 import 외 game 의존이 없음을 확인한다.”

### C2. V11 선병합 상태의 DB에 예약된 V8~V10을 뒤늦게 설치하는 경로가 빠져 있다

- 근거: `docs/tasks/TASK-005A.md:3–8`은 V11을 먼저 독립 적용할 수 있게 하고 V8~V10을 예약했다. `docs/tasks/TASK-005B.md:5–7`은 후속 게임 병합만 요구한다. `backend/src/main/resources/application.yml:1–14`는 Flyway `out-of-order` 설정이 없고, `backend/build.gradle:25–26`은 Flyway를 사용한다. V11을 이미 설치한 영속 DB에 V8~V10이 후속 추가되면 기본 `outOfOrder=false`에서는 버전 순서대로 자동 적용되지 않는다. 깨끗한 컨테이너 DB에서 전 버전을 다시 적용하는 테스트로는 드러나지 않는다.
- 수정 문구: “005A는 전용 Compose 프로젝트/DB에서만 V11을 선적용한다. 기존 데이터를 보존할 공용 DB에는 V8~V10이 준비되어 적용될 때까지 V11을 선배포하지 않는다. V11을 넣은 실습 DB를 005B에서 이어 쓸 때는 시드를 보존하고 전용 DB를 재생성하거나, 데이터 보존이 필요하면 담당자가 백업 및 V8~V10의 사후 적용 호환성을 확인한 뒤 명시적 out-of-order 마이그레이션을 검증한다. Flyway 동작의 전역 변경을 기본값으로 두지 않는다.”

## 중요

### I1. SQLSTATE 단독 변환은 계약 오류를 오분류한다

- 근거: `docs/tasks/TASK-005A.md:27`, `docs/tasks/TASK-005B.md:13`. `docs/board/V11__board.sql:45–63,154–160,167–180`에 23505/23514가 여러 의미로 존재한다. 댓글 트리거의 `parent ... not found`는 코드 미지정(`P0001`), 깊이 초과와 다른 글 부모는 둘 다 23514(`:127–139`)이고 다른 글의 대댓글이면 깊이 분기가 먼저다. RECORD 트리거도 두 오류 모두 23514이고, 없는 게임/진행 중 게임은 같은 `not ENDED` 메시지, 남의 진행 중 게임 역시 상태 분기가 먼저다(`:203–217`). 메시지의 고정 문구로 일부 분기는 구별할 수 있지만 구조화된 제약 이름이 없는 현재 SQL로 계약의 404→403→409 전체를 역추론할 수는 없다. 계약은 `docs/api/API_CONTRACT.md:252–253,275–281` 참고.
- 수정 문구: “댓글 부모의 글 소속·가시성을 먼저 확인해 404, 이어 같은 글의 ACTIVE 대댓글인지 검사해 400. RECORD는 동일 서비스 경계에서 게임 존재→소유→종료 순으로 사전 검증한다. 쓰기 경계에서 `DataIntegrityViolationException`의 원인 PostgreSQL 예외로부터 SQLState와 `getServerErrorMessage().getConstraint()`를 함께 검사하여 `ux_post_reports_target_reporter`→409 `ALREADY_REPORTED`, `ux_posts_record_game`→409 `RECORD_ALREADY_POSTED`만 변환한다. `ON CONFLICT`는 해당 유일 키를 명시한다. V11 유지 시 트리거 메시지의 영문 고정 문구는 해당 INSERT의 방어용 보조 분류만 허용하고 응답/로그에 원문을 내보내지 않는다. RECORD `not ENDED` 분류에 재조회가 필요하면 반드시 실패 트랜잭션 롤백 후 새 트랜잭션에서 실시한다. 미분류 제약 위반은 일반 500으로 남겨 조사하며, 23514를 일괄 400/409로 바꾸지 않는다.”

### I2. 댓글 트리거의 부모 잠금이 동시 대댓글에서 교착을 일으킬 수 있다

- 근거: `docs/board/V11__board.sql:127–149`는 대댓글 INSERT 중 부모에 `FOR SHARE`를 건다. `docs/tasks/TASK-005A.md:22–24`는 같은 트랜잭션에서 부모 `reply_count`를 갱신하라 한다. 두 트랜잭션이 같은 부모의 SHARE 잠금을 잡은 상태에서 각각 UPDATE로 잠금을 승격하면 교착이 가능하다.
- 수정 문구: “대댓글 쓰기는 공통 잠금 순서에 맞춰 INSERT 전에 부모를 `FOR NO KEY UPDATE` 또는 `FOR UPDATE`로 선점하고 ACTIVE/소속을 확인한 뒤 INSERT와 카운터 UPDATE를 실행한다. 같은 루트에 동시 대댓글 두 건을 생성해 둘 다 201 및 부모 `reply_count` +2임을 확인한다.”

### I3. CORS 대신 동일 출처가 기본이며 Vite 포트가 작업 환경과 다르다

- 근거: `web/AdminSecurityConfig.java:35–60`은 `/admin/**`만 `ROLE_ADMIN`으로 제한하는 단일 전역 체인이다. 기본 `permitAll`과 `/api/**` CSRF 제외로 게시판 REST는 세션/CSRF 없이 컨트롤러까지 진입한다. `cors()` 및 MVC CORS 매핑은 없다. `frontend/src/api/client.ts:3–7`은 상대 `/api` URL과 `X-Guest-Token`을 사용하나 `frontend/vite.config.ts:3` 프록시는 8081 고정이고 `docs/tasks/TASK-005A.md:3`은 8085다. `docs/DESIGN.md:428–430`의 “공유 페이지 + Spring Security 세션”도 공개 공유 계약(`docs/api/API_CONTRACT.md:261`)과 구분해야 한다.
- 수정 문구: “관리자 HTML만 기존 `/admin/**` 룰/CSRF를 사용한다. 공개 `/share/**`는 세션을 요구하지 않는다. 사용자 REST는 상대 `/api`로 동일 출처 프록시를 사용한다. 8085 실습은 Vite 프록시 target을 환경변수로 바꾸거나 8085에 맞춘 설정을 명시하고, 운영은 `/api`, `/share`, SPA 경로를 같은 호스트에서 라우팅한다. 별도 출처 배포가 요구되면 `/api/board/**`만 실제 SPA Origin/메서드/`Content-Type`, `X-Guest-Token` 헤더를 허용하고 브라우저 프리플라이트도 테스트한다. 관리자까지 CORS 또는 CSRF 제외 범위를 넓히지 않는다.”

### I4. 관리자 기존 레이아웃은 재사용 가능하나 302/HTML 처리와 로그인 설정을 명시해야 한다

- 근거: `backend/src/main/resources/templates/admin/dashboard.html:1`은 `fragments :: head/nav`를 사용하고 공통 메뉴와 `${message}` 플래시는 `backend/src/main/resources/templates/admin/fragments.html:1`에 있다. `web/AdminSecurityConfig.java:22–32,45–58`은 환경변수가 없으면 로그인할 수 없고 비인가/CSRF 실패는 403 JSON이다. `docs/api/API_CONTRACT.md:296–311`은 유효한 요청의 PRG 303, 바인딩 오류 400 HTML, 상태 충돌 409 HTML을 명시한다. 공통 advice는 범위가 전역이라 예외를 던져 처리하면 해당 오류 페이지 대신 JSON이 될 수 있다(`common/ApiExceptionHandler.java:24–26,59–72`).
- 수정 문구: “`templates/admin/board/` 뷰에서 `admin/fragments :: head/nav`를 재사용하고 게시판 링크는 공통 `nav`에 추가한다. 모든 POST 폼은 `th:action`으로 CSRF hidden 필드가 렌더되는지 확인한다. `@Valid @ModelAttribute` 바로 뒤 `BindingResult`, 오류 시 400/409 HTML과 폼 렌더링 모델을 명시한다. 성공 시 `RedirectView`에 `HttpStatus.SEE_OTHER`를 지정하고 `RedirectAttributes.addFlashAttribute("message", …)`를 사용한다(`redirect:`만 반환하지 않는다). `handled_by`는 `Principal.getName()`, 데모 로그인은 `.env`의 `ADMIN_USERNAME`, `ADMIN_PASSWORD`를 설정한다.”

### I5. 005B 공유 어댑터 수정 범위와 착수 게이트를 명확히 해야 한다

- 근거: `docs/tasks/TASK-005B.md:5–8,11–17`; 현재 `backend/src`에는 `game_records`, summary 및 `/share/` 처리 구현이 없고 `docs/api/API_CONTRACT.md:155–175,261`에 계약만 있다. 향후 공유 컨트롤러가 `game`에 추가되면 “game 패키지 수정 금지”와 “공유 컨트롤러가 BoardRecordService를 호출”을 그대로 병행할 수 없다. 또한 `docs/tasks/TASK-005B.md:21`의 ‘9필드 모두 summary 응답과 같음’에서 `serviceCode`, `areaCode`는 3장 summary 응답(:155–160)에 없다.
- 수정 문구: “005B 착수 전 공식 summary 코드/테이블/컬럼 및 실제 공유 컨트롤러 경로를 확인한다. 공유 컨트롤러가 `game`에 있으면 엔딩 선행 작업에서 공개 조회 훅을 제공하거나, 그 컨트롤러의 링크 조회 위임 한 곳만 예외 허용한다. 소유/요약/게임 처리 로직과 엔딩 데이터 쓰기는 수정하지 않는다. 테스트는 공통 7필드를 summary 응답과 비교하고 `serviceCode`/`areaCode`는 동일 엔딩 원본과 직접 비교한다(요약 계약을 유지하는 경우).”

### I6. 동시성 테스트는 현 설정으로 가능하지만 fixture와 트랜잭션 경계를 분리해야 한다

- 근거: `backend/build.gradle:19,32–39`에 Spring Security Test, JUnit, Testcontainers가 이미 있다. `GameIntegrationTest.java:30–45,78–89`는 PostGIS 정적 컨테이너 + 동적 DataSource 및 `ExecutorService`/latch 패턴을 사용한다. `AdminWebIntegrationTest.java:29–45,63–75`는 실제 보안 체인, 랜덤 포트·세션 로그인 및 CSRF 방식의 참고다. `docs/tasks/TASK-005A.md:32–42`, `docs/tasks/TASK-005B.md:20–25`: 다른 사용자 10회 테스트가 이전 좋아요 1건이 남은 동일 게시물에서 시작하면 10 기대값이 성립하지 않는다. 테스트 메서드 트랜잭션은 작업 스레드와 공유되지 않는다.
- 수정 문구: “동시성 통합 테스트는 Docker가 실행 중인 실제 PostGIS로 실행한다. 테스트 단위의 `@Transactional` 없이 fixture를 먼저 커밋하고 각 worker가 실제 HTTP/보안 체인 또는 프록시를 거친 서비스 호출로 자체 트랜잭션을 갖게 한다. 10 worker 동시 시작 후 제한 시간 내 Future 결과를 전부 확인하고, 두 좋아요 시나리오는 서로 다른 글로 분리한다. HTTP 204·관계 행 수·카운터/version을 함께 검증하고 RECORD 동시 테스트는 201/409 각각 1건과 실제 활성 행 하나를 확인한다.”

### I7. 완료 명령은 현재 셸에서 컴파일 전 의존성 해석 단계가 실패한다

- 실제 확인: 저장소 백엔드를 임시 폴더에 복사해 `backend` 안에서 `.\gradlew.bat clean check --offline --console=plain`을 실행했다. wrapper가 실행되고 `:clean`은 성공했으나 `:compileJava`의 `compileClasspath` 해석에서 Spring Boot starter, PostgreSQL 등 캐시 부족으로 종료 1. 테스트는 실행되지 않았다. 검증 환경의 캐시 문제와 게시판 코드 실패를 구분한다. 네트워크가 허용되는 개발 환경에서 먼저 `clean check`를 1회 수행해 의존성과 컨테이너 이미지(Testcontainers 보조 이미지 포함)를 준비한 뒤, 동일 Gradle 캐시를 사용해 `--offline`을 재검증한다.
- 근거: `docs/tasks/TASK-005A.md:44–48`, `docs/tasks/TASK-005B.md:27–30`; wrapper는 `backend/gradlew.bat`에만 있고 `backend/README.md:2–17`은 `cd backend` 뒤 `.\gradlew.bat` 사용, 이 셸에서 Java 25/Gradle 9.7.1 wrapper·Docker 29.4.0·PostGIS 이미지·`curl.exe`는 확인했고 호스트 `psql`은 PATH에서 못 찾았다. `docker-compose.yml:3–11`, `.env.example:13–25`: DB URL과 노출 포트를 5437로 함께 맞춰야 하며 앱 포트는 `application.yml:21–22`의 `SERVER_PORT`로 설정한다. `git grep` 기본은 새 미추적 `board` 파일을 보지 않아 커밋 금지 리뷰에서 빈 결과가 거짓 통과할 수 있다. 15건 시드의 정상 플랜은 순차 스캔일 가능성이 높다(`docs/board/V11__board.sql:83–85`).
- 수정 문구: “루트에서 `docker compose up -d db`(별도 프로젝트 사용), `Push-Location backend; .\gradlew.bat clean check --offline; Pop-Location`를 실행한다. Java 25, Gradle wrapper/의존성 캐시 및 Docker 이미지/데몬 사전 확인 후 결과 XML의 tests·failures·skipped를 합산한다. SQL은 `docker compose exec -T db psql -U <DB_USERNAME> -d seoul_startup_sim`을 사용하고, 인덱스는 실제 검색 SQL의 정상 `EXPLAIN` 결과를 보고한다(소량 시드 순차 스캔 허용; 충분한 선택도를 갖는 별도 fixture 또는 `SET LOCAL enable_seqscan=off`는 인덱스 사용 가능성 확인용으로 구분). 패키지 점검은 루트에서 `git grep --untracked -n 'game\.' -- backend/src/main/java/com/seah/startupsim/board`로 보드 디렉터리 존재와 허용 import 두 줄을 확인한다. API 시연은 Windows `curl.exe -i`, 8085, 발급한 게스트 토큰 변수, 앞 단계의 게시글 ID/version을 명시한다(출력 보고에서 토큰 제외).”

### I8. 기존 React API 헬퍼를 그대로 쓰면 204·오류 코드·무인증 조회가 깨진다

- 근거: `frontend/src/api/client.ts:4–7`은 모든 요청 전 `guest()`를 호출하고, 성공은 항상 `response.json()`을 호출하며, `ApiError`는 `status`/`message`만 보존한다. 204는 본문 없이 끝나는 삭제·좋아요·RECORD 링크 미존재의 정상 결과이며, 게시판은 같은 409끼리 코드로 구별한다(`docs/api/API_CONTRACT.md:208,217,230,253,257,281,284,292,313–317`). 이 헬퍼를 그대로 쓰면 성공한 좋아요가 JSON 파싱 실패로 UI에서 롤백되거나 공개 상세만 열어도 게스트 생성/조회수 증가가 일어난다. `docs/tasks/TASK-005C.md:5–6,9,12–16,23`의 기존 목 구조와 코드별 분기는 이 차이를 명시해야 한다.
- 수정 문구: “기존 게임 클라이언트 기본 동작을 유지하면서 게시판 요청 어댑터에는 인증 모드 none/optional/required, `ApiError.code`, 명시적 204 반환 지원을 추가한다. optional은 이미 존재하는 저장 토큰을 쓰되 자동 발급하지 않는다. 목과 실제 응답 모두 삭제/좋아요 204, RECORD 링크 204→없음, 409 코드 보존을 같은 테스트로 확인한다.”

## 사소

### M1. 의존성은 충족되어 있으며, 지시서가 구현 선택을 단순화할 수 있다

- 근거: `backend/build.gradle:17–28,32–36`에 MVC, Security, Security Test, Thymeleaf, JPA, JDBC, Validation, Flyway/PostgreSQL, Testcontainers가 이미 있다. `game/GuestAuth.java:14–25`도 JdbcTemplate을 사용한다. `docs/tasks/TASK-005A.md:18,22–27`의 부분 인덱스·`ON CONFLICT`·행 수·카운터 갱신은 PostgreSQL 네이티브 SQL 중심이다. JPA 선택 자체가 차단은 아니나 native 쿼리/flush 시점과 예외 래핑까지 계약으로 맞춰야 한다.
- 수정 문구: “게시판 신규 저장소는 `JdbcTemplate`으로 통일한다. 기존 JPA/JDBC starter는 유지하고 신규 의존성은 추가하지 않는다. 폼/REST 검증은 `jakarta.validation`, 필수 version은 누락과 0을 구분하기 위해 `Long @NotNull @PositiveOrZero`, 페이지 입력은 먼저 직접 검증한 뒤 `PageRequest`로 변환한다.”

### M2. 설계 표에서 HIDDEN 포함 여부와 댓글 오류 코드를 API 계약과 일치시킨다

- 근거: `docs/DESIGN.md:416–418`은 “공개(삭제 아님)” 및 댓글 오류 400만 표기한다. `docs/board/V11__board.sql:61–63` 부분 인덱스는 HIDDEN도 포함하며 `docs/api/API_CONTRACT.md:253,279`는 그 동작 및 다른 글/비공개 부모 404를 명시한다.
- 수정 문구: “게임당 삭제되지 않은 RECORD 1건(ACTIVE 또는 HIDDEN), DELETED 후 재작성 허용. 대댓글의 부모가 없거나 다른 글·비공개면 404, 같은 글의 ACTIVE 대댓글을 부모로 지정하면 400 `COMMENT_DEPTH_EXCEEDED`.”
