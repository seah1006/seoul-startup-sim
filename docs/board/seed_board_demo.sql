-- TASK-005A 전용 데모 DB(호스트 5437) 또는 격리된 Testcontainers에서 V11 이후 수동 1회 실행.
-- Flyway 아님. 아래 데모 사용자·상호는 가상이며, 게스트 인증용 토큰은 만들지 않는다.
BEGIN;
DO $$
DECLARE
    users_demo bigint[];
    posts_demo bigint[] := '{}';
    next_post bigint;
    next_game bigint;
    root_comment bigint;
    next_author bigint;
    i int;
    j int;
    r record;
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE nickname IN ('데모 별솜', '데모 달소리', '데모 구름벗')) THEN
        RAISE EXCEPTION '게시판 데모 시드는 이미 적재되었습니다.';
    END IF;
    WITH inserted AS (
        INSERT INTO users (nickname) VALUES ('데모 별솜'), ('데모 달소리'), ('데모 구름벗') RETURNING id
    ) SELECT array_agg(id ORDER BY id) INTO users_demo FROM inserted;

    FOR r IN SELECT * FROM (VALUES
        (1,'FREE','게시판 데모 공지','가상 사장님들의 창업 경험을 나누는 곳입니다.'),
        (2,'TIP','개점 전 체크리스트','필수 장비와 첫 달 고정비를 나누어 적습니다.'),
        (3,'QNA','첫 분기 재고량은 어느 정도인가요?','판매 예측과 폐기 위험 사이에서 고민 중입니다.'),
        (4,'FREE','처음으로 목표 매출을 달성했어요','작은 변화가 쌓이니 분기 목표가 보입니다.'),
        (5,'TIP','고정비부터 확인하는 습관','임대료와 인건비를 먼저 기록하면 비교가 편합니다.'),
        (6,'QNA','비 오는 날 영업시간 조정','운영 시간을 줄이기 전에 어떤 지표를 보나요?'),
        (7,'FREE','한 주의 운영 회고','한 번에 여러 결정을 바꾸지 않기로 했습니다.'),
        (8,'TIP','메뉴 수를 줄여 본 후기','자주 찾는 메뉴를 남기니 주문 흐름이 가벼워졌습니다.'),
        (9,'QNA','상권을 바꿔 본 분 있나요?','고정비가 낮은 곳과 유동 인구가 많은 곳을 비교 중입니다.'),
        (10,'FREE','분기 목표를 함께 적어 봐요','저는 이번 분기에 운영 시간을 일정하게 유지하려고 합니다.'),
        (11,'TIP','광고 예산을 작게 시작하는 법','적은 예산으로 두 가지 문구를 비교해 봅니다.'),
        (12,'QNA','바쁜 시간대 인력 배치','시간대별 대기열을 어떻게 줄이고 있나요?'),
        (13,'FREE','손익표를 처음 완성했어요','빠뜨린 비용이 없는지 한 번 더 확인했습니다.'),
        (14,'TIP','가격 변경 전 남겨 두는 기록','기준 기간의 매출과 방문객을 미리 저장합니다.'),
        (15,'QNA','메뉴 실험을 언제 종료하시나요?','한 분기 결과만으로 판단해도 될까요?')
    ) AS demo(seq, category, title, content) ORDER BY seq LOOP
        INSERT INTO posts (author_id, category, title, content, is_pinned, created_at, updated_at)
        VALUES (users_demo[1 + (r.seq - 1) % 3], r.category, r.title, r.content, r.seq = 1,
                now() - r.seq * interval '1 day', now() - r.seq * interval '1 day') RETURNING id INTO next_post;
        posts_demo := array_append(posts_demo, next_post);
    END LOOP;

    FOR r IN SELECT * FROM (VALUES
        (1,'CLOSED','CS100001','한식음식점','DEMO-MANGWON','망원역','별솜네모솥','별솜 사장',4,-12000000),
        (2,'SOLD','CS100010','커피-음료','DEMO-YEONNAM','연남동','달소리별빛잔','달소리 사장',9,18000000),
        (3,'LONG_RUNNING','CS100008','분식전문점','DEMO-SEONGSU','성수동','구름벗세모분식','구름벗 사장',20,33000000)
    ) AS demo(seq, ending, service_code, service_name, area_code, area_name, shop_name, owner_name, quarters, profit) LOOP
        INSERT INTO games (user_id, seed, start_quarter, last_quarter, current_turn, status, ending_type, cash, initial_capital, settings)
        VALUES (users_demo[r.seq], r.seq, '20201', '20244', r.quarters, 'ENDED', r.ending, 50000000 + r.profit, 50000000, '{}')
        RETURNING id INTO next_game;
        INSERT INTO posts (author_id, category, title, content, game_id, ending_type, service_code, service_name,
                           area_code, area_name, shop_name, owner_name, survived_quarters, total_profit)
        VALUES (users_demo[r.seq], 'RECORD', '가상 엔딩 인증: ' || r.shop_name, '데모용 창업 시뮬레이션 엔딩입니다.',
                next_game, r.ending, r.service_code, r.service_name, r.area_code, r.area_name,
                r.shop_name, r.owner_name, r.quarters, r.profit) RETURNING id INTO next_post;
        posts_demo := array_append(posts_demo, next_post);
    END LOOP;

    FOR i IN 1..cardinality(posts_demo) LOOP
        next_post := posts_demo[i];
        next_author := users_demo[1 + i % 3];
        IF i <= 6 THEN
            INSERT INTO comments (post_id, author_id, content, reply_count)
            VALUES (next_post, next_author, '경험을 공유해 주셔서 감사합니다.', CASE WHEN i <= 2 THEN 1 ELSE 0 END)
            RETURNING id INTO root_comment;
            IF i <= 2 THEN
                INSERT INTO comments (post_id, author_id, parent_id, content)
                VALUES (next_post, users_demo[1 + (i + 1) % 3], root_comment, '다음 분기 결과도 나눠 주세요.');
            END IF;
            IF i = 2 THEN
                INSERT INTO post_reports (target_type, target_id, reporter_id, reason)
                VALUES ('COMMENT', root_comment, users_demo[1], 'SPAM');
            END IF;
            UPDATE posts SET comment_count = CASE WHEN i <= 2 THEN 2 ELSE 1 END WHERE id = next_post;
        END IF;
        FOR j IN 1..(i % 4) LOOP
            INSERT INTO post_likes (post_id, user_id) VALUES (next_post, users_demo[j]);
        END LOOP;
        FOR j IN 1..(i % 8) LOOP
            INSERT INTO post_views (post_id, user_id, viewed_on)
            VALUES (next_post, users_demo[1 + j % 3], (now() AT TIME ZONE 'Asia/Seoul')::date - j);
        END LOOP;
        UPDATE posts SET like_count = i % 4, view_count = i % 8 WHERE id = next_post;
    END LOOP;
    INSERT INTO post_reports (target_type, target_id, reporter_id, reason)
    VALUES ('POST', posts_demo[4], users_demo[2], 'SPAM');
END
$$;
COMMIT;
