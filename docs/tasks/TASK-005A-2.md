# TASK-005A-2: 게시판 백엔드를 최신 main과 통합 + 회원 전용 쓰기

- 브랜치: `task/TASK-005A` (worktree `C:\Users\my\dev\sss-005a`, DB 5439, 앱 8087)
- 상태: 최신 main을 병합하는 중 충돌 2개 파일이 남은 상태로 넘겨준다(`application.yml`, `templates/admin/fragments.html`). 양쪽 설정·메뉴를 모두 살려 해결한다.
- 선행: `AGENTS.md`, `docs/DESIGN.md` 19·20장, `docs/api/API_CONTRACT.md` 4·5장, `docs/tasks/TASK-005A-PROGRESS.md`

## 할 것
1. 충돌 해결 후 병합 커밋. Flyway 순서: main에 V1~V5, V7, V7_1, V7_2, V8이 있고 이 브랜치의 V11이 뒤에 온다. 테스트(Testcontainers 새 DB)에서 순서대로 적용되는지 확인.
2. **회원 전용 쓰기** (계약 5장): 게시판 쓰기(글 작성·수정·삭제, 댓글, 좋아요, 신고)와 내 글 조회는 MEMBER만. 게스트 토큰 → 403 `MEMBER_REQUIRED`, 토큰 없음 401. 읽기는 그대로 공개.
   - `BoardGuestAuth`는 main의 `auth` 패키지(인증 서비스·`AuthenticatedUser`·역할)를 사용하도록 바꾼다. game 패키지 의존(`GameException`, `GuestAuth`)을 없앤다.
   - 기존 게스트 기반 테스트는 지우지 말고 회원 토큰으로 바꾸고, 게스트 403 테스트를 추가
3. 탈퇴 사용자의 글·댓글 작성자 표시가 "탈퇴한 사장님"으로 나오는지 테스트
4. 게스트 발급 남용 방지(리뷰 2-1)는 main의 IP 필터 + 회원 전용 쓰기로 해결됨을 PROGRESS에 기록
5. 전체 `gradlew.bat clean check --offline` 통과(테스트 수), PROGRESS에 "main 통합·회원 전용" 절

## 하지 않을 것
main 병합(메인 에이전트가 V9·V10 이후에 한다), 프론트엔드 수정, game/auth 패키지 수정
