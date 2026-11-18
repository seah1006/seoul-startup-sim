# TASK-006A: 회원 인증 백엔드 (가입·로그인·로그아웃·내 계정 CRUD, Spring Security)

- 브랜치: `task/TASK-006A` (worktree `C:\Users\my\dev\sss-006a`, DB 5441, 앱 8089)
- 담당: Codex
- 선행: `AGENTS.md`, `docs/DESIGN.md` **20장**, `docs/api/API_CONTRACT.md` **5장**(이 계약이 기준) + 공통, `web/AdminSecurityConfig`, `game/GuestAuth`
- **Flyway: `V7_2__auth.sql` 만 사용** (V7_1은 TASK-004E, V8 이후는 다른 작업)
- 동시 작업: TASK-004B-1(game/event, V8), TASK-004E(ingest·catalog·common의 IP 제한 필터, V7_1). 
  - game 패키지는 `GuestAuth` 한 파일만 수정 가능(인증을 새 `auth` 패키지에 위임하도록).
  - IP 제한은 004E가 common에 만드는 필터를 쓴다. 이 작업에서는 만들지 말고, 가입·로그인 경로가 그 필터 설정 목록에 들어가도록 설정 키만 추가(필터가 아직 main에 없으면 TODO 주석 + 보고).

## 만들 것
1. `V7_2__auth.sql`
   - `users`에 `login_id text UNIQUE`, `password_hash text`, `role` 값에 `MEMBER` 허용(기존 `USER`/`GUEST` 의미 정리: 기존 행은 게스트), `deleted_at timestamptz`, `failed_login_count int`, `locked_until timestamptz`, `updated_at`
   - nickname 유일: 활성 사용자 기준 부분 유일 인덱스(`WHERE deleted_at IS NULL`). 기존 게스트 닉네임 중복이 있으면 마이그레이션에서 처리 방법 결정·보고
   - `auth_sessions (id bigserial, user_id FK, token_hash char(64) UNIQUE, kind text check in ('GUEST','MEMBER'), created_at, last_used_at, expires_at, revoked_at)` + `(user_id) WHERE revoked_at IS NULL` 인덱스
   - 기존 `users.guest_token_hash`는 `auth_sessions`(kind GUEST)로 옮기고 컬럼은 남기되 더 이상 쓰지 않음(다음 정리 작업에서 삭제) — 이유 주석
   - CHECK: 회원이면 login_id·password_hash NOT NULL
2. `auth` 패키지 (Controller → Service → Repository)
   - 토큰 발급·해시·검증·만료 연장·폐기, BCrypt(`PasswordEncoder` 빈)
   - 계약 5장의 엔드포인트 전부. 게스트 → 회원 전환은 한 트랜잭션(사용자 행 잠금, 게스트 세션 폐기, 회원 세션 발급)
   - 로그인 실패 카운트·잠금은 사용자 행 갱신으로(동시 실패에도 카운트 정확히: `UPDATE … SET failed_login_count = failed_login_count + 1`)
   - 아이디 존재 여부를 응답·시간으로 드러내지 않기: 없는 아이디도 더미 해시로 BCrypt 비교
3. **Spring Security 통합**
   - `/api/**` 체인에 `ApiTokenAuthenticationFilter`: 헤더 토큰 → `SecurityContext`에 `AuthenticatedUser(userId, role)` + 권한 `ROLE_GUEST`/`ROLE_MEMBER`. 무상태(STATELESS), CSRF 제외(지금처럼).
   - 관리자 체인(`/admin/**`, 세션 폼 로그인)은 그대로. 두 체인을 `securityMatcher`로 분리.
   - 401/403 응답은 공통 에러 형식(`UNAUTHORIZED`, `MEMBER_REQUIRED`)
   - 컨트롤러는 `@AuthenticationPrincipal AuthenticatedUser` 또는 공용 리졸버로 userId를 받는다
4. `GuestAuth.resolve(token)`는 새 인증 서비스에 위임(게스트·회원 토큰 모두 받아 userId 반환) → 게임 코드와 아직 main에 없는 게시판(005A, `BoardGuestAuth` → `GuestAuth.resolve`)이 수정 없이 회원 토큰으로도 동작. `GuestAuth.create()`도 `auth_sessions`에 저장.
5. 회원 전용 판정을 위한 공용 메서드 `requireMember(userId)` 또는 `@PreAuthorize("hasRole('MEMBER')")` 기반 준비(게시판 적용은 005A 병합 작업에서)
6. 탈퇴: 소프트 삭제·닉네임 익명화·세션 폐기. 랭킹·게시글 표시용 닉네임은 그대로 users를 조인하면 자동으로 "탈퇴한 사장님"이 되는지 확인(엔딩 기록 스냅샷 닉네임은 V9의 정책을 따름 — 지금 없음)

## 테스트 (Testcontainers + MockMvc)
- 가입(새 사용자), 게스트 상태 가입 → 같은 userId·게임 유지·게스트 토큰 폐기, 중복 아이디/닉네임 409, 형식 400
- 로그인 성공/실패 같은 메시지, 5회 실패 → 423, 잠금 해제 시간 경과(시계 주입) 후 성공
- 동시 실패 10건 → 카운트 정확
- 로그아웃 후 같은 토큰 401, 비밀번호 변경 → 다른 세션 401·현재 세션 유지
- 탈퇴 → 로그인 401, 닉네임 익명화, 재가입 같은 loginId 409
- 만료 토큰 401(시계 주입), `X-Guest-Token`과 `Authorization` 둘 다 동작
- 게임 API 전체 기존 테스트 통과(게스트 토큰), 회원 토큰으로도 게임 진행 가능
- 비밀번호·토큰이 로그와 응답 에러에 없음
- `/admin/**` 기존 보안 테스트 통과

## 완료 조건
1. `gradlew.bat clean check --offline` 통과 (테스트 수)
2. `curl.exe -i` 시연 스크립트 `docs/tasks/TASK-006A-demo.ps1`: 게스트 → 게임 생성 → 가입(전환) → 같은 게임 조회 → 로그아웃 → 로그인 → 내 정보 수정 → 비밀번호 변경 → 탈퇴
3. API_CONTRACT 5장과 실제 응답이 다르면 구현을 계약에 맞춘다. 계약 변경이 필요하면 "제안"으로 보고
