# AGENTS.md (에이전트 작업 규칙)

이 저장소에서 일하는 모든 AI 에이전트(Claude, Codex)가 따르는 규칙입니다. 설계는 `docs/DESIGN.md`가 기준입니다.

## 역할
- **메인 에이전트(Aside)**: 작업 지시서 작성, 리뷰, 검증, 병합, push. 사용자와 소통.
- **구현 에이전트(Codex)**: `docs/tasks/TASK-XXX.md` 지시서대로 구현. 지시서 범위 밖은 건드리지 않는다.
- **조사·리뷰 에이전트(Claude)**: 데이터 조사, 코드 리뷰, 문서화.

## 절대 규칙
1. **비밀 값 금지**: API 키, 비밀번호를 코드, 문서, 테스트, 커밋 메시지, 로그 출력에 쓰지 않는다. 키는 루트 `.env`(gitignore 됨)에서 환경 변수로만 읽는다. `.env` 내용을 출력하지 않는다.
2. **push 금지**: 구현 에이전트는 자기 작업 브랜치에 커밋까지만 한다. `main` 병합과 push는 메인 에이전트가 한다.
3. **지시서 범위 지키기**: 지시서에 없는 기능, 의존성, 파일 구조 변경은 하지 않는다. 필요하면 결과 보고에 "제안"으로 적는다.
4. **검증하고 보고**: 끝내기 전에 지시서의 "완료 조건" 명령을 직접 실행하고, 실제 출력 요약을 보고에 붙인다. 실행하지 못한 검증은 "미검증"이라고 명시한다.
5. **실제 데이터 정직성**: 외부 데이터의 단위, 좌표계, 필드 의미를 추측으로 쓰지 않는다. 확인한 근거(응답 샘플, 공식 문서)를 남기고, 확인 못 한 것은 "미확인"으로 표시한다.
6. **실제 상호 사용 규칙**: 실제 가게 상호는 사실 정보 표시에만 쓴다. 가상 이벤트, 가상 반응에는 쓰지 않는다.

## 기술 기준
- Java 25 (Temurin), Spring Boot, Gradle(Groovy DSL, `build.gradle`), PostgreSQL + PostGIS, Flyway
- 백엔드 코드는 `backend/`, 프론트엔드(React, Vite)는 `frontend/` (아직 없음)
- 패키지 루트: `com.seah.startupsim`
- 계층: Controller → Service → Repository. 외부 API 응답은 별도 DTO로 받고 엔티티와 분리.
- 테스트: JUnit 5, DB가 필요한 테스트는 Testcontainers(PostGIS 이미지). 외부 API는 WireMock.
- 커밋 메시지: `feat:`, `fix:`, `docs:`, `test:`, `chore:`, `refactor:` + 한국어 요약

## 로컬 실행
- 포트: DB 5433, 앱 8081 (다른 로컬 프로젝트와 충돌 방지).
- DB: `docker compose up -d` (루트 `docker-compose.yml`)
- 백엔드: `cd backend && ./gradlew bootRun` (Windows는 `gradlew.bat`)
- 전체 검증: `cd backend && ./gradlew clean check`
- 저장소 경로에 한글이 없어야 한다(일부 도구가 한글 경로에서 실패). 현재 `C:\Users\my\dev\seoul-startup-sim`.

## 문서 위치
- 설계: `docs/DESIGN.md`
- 작업 지시서: `docs/tasks/`
- 데이터 조사 결과: `docs/data/`
- 결정 기록: `docs/DESIGN.md` 16장
