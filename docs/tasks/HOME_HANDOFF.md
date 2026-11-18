# 다른 PC에서 이어서 작업하기

## 브랜치

- `task/TASK-004B-spec`: TASK-004B 지시서, API 계약 변경, V8/V9 스키마 초안과 시드. 실행 코드나 활성 마이그레이션은 아님. [지시서](TASK-004B.md) 참고.
- `task/TASK-005A`: Spring 게시판 백엔드와 V11. 이 브랜치의 [진행 기록](https://github.com/seah1006/seoul-startup-sim/blob/task/TASK-005A/docs/tasks/TASK-005A-PROGRESS.md)에 구현·검증·잔여 항목 기록.
- `task/TASK-005C`: React 게시판. 이 브랜치의 [진행 기록](https://github.com/seah1006/seoul-startup-sim/blob/task/TASK-005C/docs/tasks/TASK-005C-PROGRESS.md)에 목/실제 연동 검증 범위를 구분.

이미 클론한 경우 저장소 루트에서:

```powershell
git fetch origin
git worktree add ../sss-005a --track -b task/TASK-005A origin/task/TASK-005A
git worktree add ../sss-005c --track -b task/TASK-005C origin/task/TASK-005C
git worktree add ../sss-004b-spec --track -b task/TASK-004B-spec origin/task/TASK-004B-spec
```

집에 저장소가 없으면 먼저 `git clone https://github.com/seah1006/seoul-startup-sim.git`을 실행한다. 같은 로컬 작업 브랜치가 이미 있으면 새로 만들지 말고, 변경 사항을 보존한 뒤 해당 브랜치에서 `git pull --ff-only`를 사용한다.

## 005A: 별도 DB에서만 실행

- Docker Desktop과 JDK 25가 필요하다. 작업 트리 루트에서 `.env.example`을 `.env`로 복사하고 로컬용 DB·관리자 자격 증명을 직접 설정한다. `.env`는 커밋하지 않는다.
- `DB_PORT=5437`, `SERVER_PORT=8085`, `DB_URL=jdbc:postgresql://127.0.0.1:5437/seoul_startup_sim`으로 맞춘다. **기존 5433 DB에 V11을 적용하지 않는다.**
- 작업 트리 루트에서 `docker compose -p sss-005a up -d db`로 프로젝트/볼륨도 분리한다. `backend`에서 `./gradlew.bat bootRun`, 테스트는 `./gradlew.bat clean check`(의존성 캐시 준비 뒤 `--offline` 가능). 테스트용 DB는 Testcontainers가 별도로 만든다.

## 005C: 목으로 바로 확인

- `frontend`에서 `npm ci` 후 `npm run dev`. 기본 목 모드이며 `/board`를 연다.
- 실제 게시판 백엔드를 사용하려면 프론트 실행 셸에서 `$env:VITE_USE_MOCK='false'`, `$env:VITE_API_TARGET='http://localhost:8085'`를 설정한 뒤 Vite를 시작한다. 실제 서버에서 미구현인 기능은 목 응답으로 자동 대체되지 않는다.
- 이 PC의 Node 26에서 jsdom `localStorage` 오류가 나면 테스트 실행 전에 `$env:NODE_OPTIONS='--no-experimental-webstorage'`를 설정한다. `npm run`이 `cmd.exe` 경로 오류로 실패할 때만 `$env:npm_config_script_shell=(Get-Command pwsh).Source`를 설정한다.

## 병합 순서

004A 병합 확인 → 004B 파트 1(V8), 파트 2(V9) → 004D(V10) → 005A(V11). `outOfOrder=false`를 유지한다. 005B는 실제 엔딩 저장 및 공유 페이지, 005A가 병합된 뒤 착수한다. 그전에는 목 또는 시드 RECORD만 사용한다. `main`과 개인 로컬 DB·비밀값은 이 인계에 포함하지 않는다.
