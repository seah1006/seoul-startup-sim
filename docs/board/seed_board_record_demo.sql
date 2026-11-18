-- TASK-005B 시연용: RECORD를 API로 작성할 ENDED 게임 1개(인증글 미작성)를 만든다. Flyway 아님, 수동 실행.
-- 먼저 회원 가입 API로 계정을 만든 뒤, 그 loginId를 넘겨 실행한다. (비밀번호 해시는 SQL로 만들지 않는다)
--   docker compose -p sss-005b exec -T db psql -U <사용자> -d <DB> -v owner_login=<loginId> -f - < docs/board/seed_board_record_demo.sql
-- 출력된 game_id로 POST /api/board/posts {category:RECORD, gameId, title, content} 를 호출한다.
\set ON_ERROR_STOP on
BEGIN;
WITH owner AS (
    SELECT id FROM users WHERE login_id = :'owner_login' AND deleted_at IS NULL
), game AS (
    INSERT INTO games (user_id, seed, start_quarter, last_quarter, current_turn, status, ending_type, cash, initial_capital, settings)
    SELECT id, 77, '20201', '20244', 8, 'ENDED', 'LONG_RUNNING', 83000000, 50000000, '{"initialCapital": 50000000}'::jsonb FROM owner
    RETURNING id, user_id
)
INSERT INTO game_records (game_id, user_id, nickname, ending_type, survived_quarters, total_profit, best_quarter_profit,
                          shop_count, area_code, area_name, service_code, service_name, shop_name, owner_name, ranking_date)
SELECT g.id, g.user_id, u.nickname, 'LONG_RUNNING', 8, 33000000, 9000000, 1, 'DEMO-MANGWON', '망원역',
       'CS100010', '커피-음료', '시연 달빛커피', '시연 사장', (now() AT TIME ZONE 'Asia/Seoul')::date
FROM game g JOIN users u ON u.id = g.user_id
RETURNING game_id;
COMMIT;
