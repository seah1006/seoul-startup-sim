# TASK-004F 완료 보고

## 구현
- 진행 중이던 5개 충돌을 해결하고 게임 2차와 게시판을 병합했다. 엔딩 요약과 인증 진입점을 마지막 분기 결과 화면 및 종료 게임 화면에 함께 표시한다.
- 게임 목 저장소 v2와 게시판 v1을 분리 유지하며 게시판용 엔딩 시드와 게임 ID 충돌을 방지한다.
- Prettier 3.5.3, printWidth 100, singleQuote, semi 및 format/format:check를 도입했다.
- Home, NewOwner, ShopSetup, History, GamePage를 pages로 분리하고 목 요청의 카탈로그/게임 분기를 경로별 함수 테이블로 전환했다.
- 인구와 개폐업률은 number | null이며 null은 자료 없음, 0은 숫자/0%로 표시한다.
- 게임과 게시판은 공통 401 복구 함수를 사용한다. 필수 인증은 토큰 제거 후 1회 재발급/재시도, 선택 인증은 토큰 제거 후 익명 1회 재시도한다. 재발급은 새 게스트 신원이며 과거 소유권을 복구하지 않는다.
- CLOSED/SOLD/LONG_RUNNING별 기존 문구에 회귀 테스트를 추가했다.

## 검증
- npm run format:check: 통과
- npm run lint: 통과
- npx vitest run: 7개 파일, 60개 테스트 통과
- npm run build: TypeScript 및 Vite 프로덕션 빌드 통과
- git diff --check: 통과
- 기준 합집합 52개 유지: 004C 19개, 005C 37개 중 app.test.tsx 공통 4개를 중복 계산하지 않음(19 + 37 - 4 = 52). 리뷰 회귀 8개를 추가해 최종 60개.
- 브라우저 실서버 E2E는 이번 구현 실행에서 수행하지 않음. 프론트 단위/컴포넌트 및 fetch 대역 검증 기준이다.

## 커밋 순서
1. c32248b 충돌 해결 병합
2. 5417b94 포맷
3. 072b011 페이지 및 목 라우트 분리
4. 리뷰 수정과 회귀 테스트

push하지 않았다.
