# TASK-006A 검증 기록

## 관리자 로그인 회귀 원인

회원 인증에서 등록한 전역 `BCryptPasswordEncoder`와 관리자 해시에 남아 있던 `{bcrypt}` 접두사가 맞지 않았다. 관리자도 같은 `PasswordEncoder` 빈으로 인코딩하도록 바꿨다. CSRF를 비활성화하거나 테스트 헬퍼를 우회하지 않았다. 수정 후 `SeoulCollectorIntegrationTest`와 `AdminWebIntegrationTest`가 통과했다.

## 인증 보강 테스트

`AuthIntegrationTest` 3개, `AuthWebIntegrationTest` 7개 통과:

- 게스트 전환 시 같은 사용자 ID, 게스트 세션 폐기, 회원 토큰 게임 인증
- 비밀번호 변경 시 다른 세션 폐기, 탈퇴 후 아이디 재사용 금지
- 보호 경로 무인증·무효 토큰 401, 게스트 회원 기능 403
- Authorization 우선순위, 회원 정보·닉네임·비밀번호·탈퇴 HTTP 처리
- UTF-8 72바이트 초과 비밀번호 400
- 주입 시계로 토큰 만료·회원 토큰 사용 시 연장, 잠금 시간 경과
- 동시 실패 10건 카운트 정확성
- 공통 IP 제한 필터에서 21번째 인증 요청 429
- Flyway 설치 순서 V7_1 → V7_2

## 실제 HTTP 시연

2026-11-18, worktree 전용 PostgreSQL 5441, 앱 8089에서 `TASK-006A-demo.ps1` 실행:

```text
POST /api/guest -> 201
POST /api/games -> 201
POST /api/auth/signup -> 201
GET /api/games/1 -> 200
POST /api/auth/logout -> 204
POST /api/auth/login -> 200
PATCH /api/auth/me -> 200
PUT /api/auth/me/password -> 204
DELETE /api/auth/me -> 204
회원 전환·게임 유지·계정 CRUD 시연 완료
```

전용 DB가 비어 있어 게임 생성의 선행 조건인 활성 매출 자료를 준비했다. 저장소 `docs/data/samples/seoul_VwsmTrdarSelngQq_20262.json` 첫 행의 원래 분기·상권·업종·매출·건수를 사용했다. 위치 자료는 이번 인증 시연에 필요하지 않아 해당 상권 도형과 중심점은 `EMPTY`로 명시했으며 임의 좌표로 대체하지 않았다. 전체 지역 자료나 실제 창업 위치 검증을 수행했다는 의미는 아니다. 토큰·비밀번호는 출력하거나 이 문서에 저장하지 않았다.

전용 DB에서도 `flyway_schema_history`의 설치 순서가 `7.1`, `7.2`임을 직접 확인했다.
