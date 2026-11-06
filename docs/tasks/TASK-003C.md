# TASK-003C: 관리자 화면(Thymeleaf) + 보안 + 공통 에러 처리 정리

- 브랜치: `task/TASK-003C` (worktree `C:\Users\my\dev\sss-003c`, DB 5435, 앱 8083)
- 담당: Codex
- 선행 문서: `AGENTS.md`, `docs/DESIGN.md` 12~14장, `docs/api/API_CONTRACT.md` 공통
- **Flyway: `V6__admin.sql` 만 사용 (필요할 때만)**

## 목표
수업(Spring MVC, 뷰 템플릿)과 포트폴리오(수집 파이프라인을 "보이게")를 동시에 만족하는 서버 렌더링 관리자 화면.

## 만들 것
1. **Spring Security**
   - `/admin/**`(화면과 `/admin/api/**`)는 ROLE_ADMIN. 폼 로그인(Thymeleaf 로그인 페이지), 관리자 계정은 환경 변수 `ADMIN_USERNAME`, `ADMIN_PASSWORD`(BCrypt로 메모리 사용자). 값이 없으면 관리자 기능 비활성 + 경고 로그
   - `/api/**`는 지금처럼 공개(게스트 토큰 검증은 003A가 별도로 함). CSRF: 관리자 화면은 켜고, `/api/**`는 끔
   - 기존 임시 플래그 `app.admin.enabled`와 `@ConditionalOnProperty`를 제거하고 보안으로 대체 (IngestController, QuarterBackfillController)
2. **공통 에러 처리 정리**
   - 지금 `ApiExceptionHandler`는 예상 못 한 예외를 **로그 없이** 500으로 바꾼다 → 마스킹된 메시지와 스택으로 ERROR 로그
   - 404(NoResourceFound/NoHandlerFound), 405, 400(검증 실패, 타입 변환, 필수 파라미터 누락)을 계약 코드로 매핑
   - `area/AreaExceptionHandler`를 공통으로 합치고 중복 제거
   - 테스트: 각 상태 코드별 응답 형식
3. **관리자 화면 (Thymeleaf + 레이아웃 공통 조각)**
   - `/admin` 대시보드: 출처별 카드(마지막 성공 시각, 최근 10회 성공률, 마지막 건수, 실행 중 여부, 서킷 브레이커 상태 — `SeoulOpenApiClient`의 CircuitBreaker 상태 노출)
   - `/admin/ingest/runs`: 수집 실행 목록(필터: source, status, 페이지네이션)
   - `/admin/ingest/runs/{id}`: 상세 + raw_payloads 목록(request_key, 크기, checksum) + 원본 JSON 보기(앞 20KB)
   - `/admin/ingest/run`: 폼(source 선택, period 입력) → 실행 → 결과로 리다이렉트(Post/Redirect/Get), 플래시 메시지
   - `/admin/ingest/backfill`: 폼(source, from, to)
   - `/admin/data-versions`: 목록(source, period, status, row_count, 시각)
   - 화면 문구는 한국어, 깔끔한 기본 CSS(`static/admin.css`), 템플릿에 비밀 값 노출 금지
4. Thymeleaf 데이터는 Controller → Service → Repository 계층으로(템플릿에서 SQL 금지). 조회용 DTO record 사용

## 테스트
- 보안: 비로그인 `/admin` → 로그인 페이지로 리다이렉트, `/admin/api/**` → 401, 로그인 후 200
- MockMvc로 대시보드/목록/상세 렌더링(모델 속성과 핵심 문구)
- 에러 처리 테스트

## 하지 않을 것
게임 API, 프론트엔드, 전환점·반응·설정 편집 화면(다음 작업)

## 완료 조건
1. `gradlew.bat clean check --offline` 통과 (테스트 수). 의존성(spring-boot-starter-security, thymeleaf-extras-springsecurity 등) 추가 시 메인에게 "의존성 준비 요청"
2. 로컬에서 로그인 → 대시보드 → 실행 목록 화면이 뜨는 것까지 확인. 화면 캡처는 메인이 함
3. `.env.example`에 `ADMIN_USERNAME`, `ADMIN_PASSWORD` 추가
