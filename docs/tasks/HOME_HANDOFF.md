# 다른 PC에서 이어서 작업하기 (2026-11-19 갱신)

## 현재 상태
- `main`에 V1~V13 전부 병합. 백엔드 테스트 219개, 프론트 테스트 79개 통과.
- 완료: 수집 파이프라인, 데이터 버전 보존, 게임 엔진, 전환점·반응·순간, 엔딩·랭킹·공유, 회원 인증, 게시판·엔딩 인증글, 밸런스, QA-1·QA-2 반영.
- 예전 작업 브랜치(`task/TASK-004B-spec`, `task/TASK-004D`, `task/TASK-005A`, `task/TASK-005C`, `review/REVIEW-1`)는 모두 main에 들어갔다. 새 작업은 main에서 새 브랜치로.

## 다음 할 일
1. **배포 (무료/저가 클라우드로 결정됨)**: Oracle Cloud 무료 VM 등에 docker compose(PostGIS + 백엔드 + 프론트 정적 파일) 구성. 비밀값은 서버 `.env`에만. 카카오 JavaScript SDK 도메인에 배포 주소 추가 필요.
2. 발표 자료: 마감일 정해지면 시작.

## 처음 받는 PC에서
```powershell
git clone https://github.com/seah1006/seoul-startup-sim.git
cd seoul-startup-sim
copy .env.example .env   # 키·DB·관리자 값은 직접 채운다 (커밋 금지)
docker compose -p sss up -d
cd backend; .\gradlew.bat clean check   # 테스트는 Testcontainers라 데이터 없이 돌아간다
```
- 줄바꿈은 `.gitattributes`로 LF 통일. 이미 클론한 PC는 `git pull` 후 `git rm --cached -r -q . ; git reset --hard`로 한 번 정리.
- 프론트: `cd frontend; npm ci; npm run dev` (기본 목 모드, 실제 백엔드는 `$env:VITE_USE_MOCK='false'`)
- Node 26에서 jsdom localStorage 오류가 나면 `$env:NODE_OPTIONS='--no-experimental-webstorage'`.

## 실제 데이터가 필요하면
- 원본 파일(`data/raw/`: 상권 SHP, 상가업소 CSV 287MB)과 메인 DB는 git에 없다. 집 PC에만 있다.
- 새 DB에 적재: 앱 실행 후 관리자(`/admin`) 로그인 → 수집/백필 화면에서 `seoul-area-shapes`(SHP 필요), `seoul-area-meta`, 분기 데이터 백필(`seoul-area-sales`·`seoul-area-stores`·`seoul-area-floating-pop` 20211~20262), `seoul-area-workers`, `sbiz-stores-csv`(CSV 필요).
- SHP·CSV 받는 곳은 `docs/data/DATA_NOTES.md` A-3, B-3.

## 포트 (집 PC 기준, 학교 PC는 자유)
- 집 PC는 다른 프로젝트가 5432/8080을 써서 DB 5433, 앱 8081.
