# 게시판(커뮤니티) 설계 작업 브리프 (메인 에이전트 작성, 2026-11-18)

저장소 루트: `C:\Users\USER\.aside\u\0\sessions\2026-11-18_F7ON8Q0VRcbTcAC3\artifacts\seoul-startup-sim`
(GitHub `seah1006/seoul-startup-sim` main 스냅샷. 로컬 git으로 base 커밋만 있음. **git commit/push 금지**, 파일만 쓴다.)

## 왜 하는가
- 이 프로젝트는 대학 "프레임워크(백엔드) 실습" 기말과제 + 포트폴리오. 다른 학생들은 디시인사이드 같은 커뮤니티, 쇼핑몰을 많이 낸다.
- 사용자는 "싱글 창업 시뮬인데도 CRUD 게시판을 제대로 보여줄 수 있는가"를 걱정한다. 평가에서 게시판 CRUD(목록 페이징·검색·정렬, 상세, 작성·수정·삭제, 권한, 댓글, 좋아요)가 커뮤니티 과제 수준으로 보여야 한다.
- 동시에 "게임에 억지로 붙인 게시판"이 아니라 게임 데이터와 연결돼야 한다 (차별점).

## 먼저 읽을 것
- `AGENTS.md` (작업 규칙), `docs/DESIGN.md` (특히 1, 6, 7, 11, 12, 14~17장), `docs/api/API_CONTRACT.md` (형식, 공통 규칙, 3장 v1.1)
- `backend/src/main/resources/db/migration/V5__game.sql` (users, games, turn_results), `backend/.../game/GuestAuth.java`, `backend/.../common/ApiExceptionHandler.java`, `backend/.../web/AdminSecurityConfig.java`
- 기존 지시서 형식 예시: `docs/tasks/TASK-004A.md`, `docs/tasks/TASK-004C.md`

## 메인 에이전트가 정한 전제 (바꾸려면 근거와 함께 메인에게 제안)
1. 새 패키지 `com.seah.startupsim.board` (game 패키지는 TASK-004A 리팩터링 중이라 수정 금지. 읽기 전용 조회만).
2. Flyway: **`V11__board.sql`** (V8·V9는 다음 게임 작업 예약, V10은 TASK-004D 밸런스). 기존 마이그레이션 수정 금지.
3. 인증: 기존 `X-Guest-Token` + `GuestAuth.resolve()`로 user_id를 얻는다. 게스트도 글을 쓸 수 있다 (카카오 로그인은 아직 미구현, 나중에 같은 users 행이 정회원으로 전환되므로 글 소유권 유지). 작성자 판정은 user_id 기준. 관리자(role=ADMIN) 기능은 기존 `/admin` Thymeleaf + Spring Security 세션.
4. 게시판 구성 후보
   - (A) 사장님 커뮤니티: 카테고리 FREE(자유) / TIP(공략) / QNA(질문) / RECORD(엔딩 인증글)
   - RECORD 글은 **끝난 내 게임(game_id, status=ENDED)** 을 첨부. 첨부 시점의 엔딩 요약(업종, 상권, 생존 분기, 누적 순이익, 엔딩 종류)을 글에 스냅샷으로 저장해 목록에서 업종·상권 필터, 정렬이 가능하게. 남의 게임 첨부 금지, 같은 게임 중복 인증 정책 결정 필요.
   - (B) 엔딩 공유 페이지 `/share/{gameId}`(계약 3장)와 RECORD 글 연결 (공유 페이지에 인증글 링크/댓글 수 등)
   - (C) 동네 반응(turn_results.reactions, 가상 리뷰)에 사장님이 답글 → 게임 효과. **게임 결정론 원칙**(시드+선택+데이터 버전이 같으면 결과 동일)과 game 패키지 수정 금지 때문에 MVP에 넣을지, 넣는다면 "플레이어 선택"으로 기록해 결정론을 지키는 방법이 무엇인지 판단할 것.
5. 기능 체크리스트 후보: 목록(Pageable 페이징, 정렬 latest/popular/views/comments, 검색 title/content/title+content/author, 카테고리·업종·상권 필터), 상세(조회수), 작성(@Valid 검증), 수정(작성자만, 낙관적 락 version), 삭제(작성자·관리자, soft delete), 댓글 + 대댓글 1단계, 좋아요(사용자당 1회, 취소 가능, 동시성), 신고 → 관리자 숨김, 공지 고정(notices 테이블 계획과의 관계 정리), 이미지 업로드는 범위 판단.
6. 화면: 게임은 React. 게시판 화면을 React(REST)로 할지 Thymeleaf(전통 Spring MVC 폼, PRG, BindingResult)로 할지는 열린 질문. 메인 기본안: 사용자 화면은 React + REST, 관리자 신고 처리·숨김은 Thymeleaf. 강의가 Spring MVC 중심이라는 점을 고려해 의견을 낼 것.
7. 스타일: 기존 계약 문서와 같은 형식(Base `/api`, 공통 에러 형식 `{code,message,timestamp}`, 한국어). 금액 원 단위 long.

## 산출물 위치 (각자 자기 파일만 쓴다)
- `docs/board/drafts/DB_DRAFT.md` (DB 담당)
- `docs/board/drafts/API_DRAFT.md` (API 담당)
- `docs/board/drafts/PRODUCT_DRAFT.md` (기획·평가 담당)
- 리뷰 의견은 `docs/board/drafts/REVIEW_<담당>.md`
- 최종 통합(DESIGN.md 19장, API_CONTRACT 4장, TASK-005A/B 지시서)은 메인 에이전트가 한다.

## 소통 규칙
- 테이블명·컬럼명·enum 값·에러 코드는 DB 담당과 API 담당이 **직접 메시지로 맞춘다** (message_agent). 합의한 이름은 양쪽 문서에 같은 표기로.
- 기획 담당은 게임 연동 요구사항과 평가 체크리스트를 두 담당에게 보내고, 두 초안이 그 체크리스트를 다 덮는지 확인한다.
- 의견이 갈리면 각 안의 장단점을 적어 메인에게 보고한다. 메인이 결정한다.
- 비밀 값 금지, 실제 상호 사용 규칙 준수(AGENTS.md).
