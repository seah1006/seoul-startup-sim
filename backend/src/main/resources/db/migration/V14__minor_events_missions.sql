-- TASK-010: 기존 게임과 전환점은 기본값으로 기존 의미를 유지한다.
ALTER TABLE turning_points
    ADD COLUMN tier text NOT NULL DEFAULT 'MAJOR' CHECK (tier IN ('MAJOR', 'MINOR')),
    ADD COLUMN source text NOT NULL DEFAULT 'STORY' CHECK (source IN ('STORY', 'DATA'));
ALTER TABLE games
    ADD COLUMN mission_code text NOT NULL DEFAULT 'SURVIVE_3Y'
        CHECK (mission_code IN ('SURVIVE_3Y', 'PROFIT_100M', 'AREA_TOP3', 'BLACK_4', 'SECOND_SHOP')),
    ADD COLUMN mission_achieved_turn int;
ALTER TABLE game_records
    ADD COLUMN mission_code text NOT NULL DEFAULT 'SURVIVE_3Y',
    ADD COLUMN mission_achieved boolean NOT NULL DEFAULT false;
INSERT INTO game_settings (key, value) VALUES
    ('event.minor.enabled', 'true'),
    ('event.minor.rate', '0.9'),
    ('event.minor.dataFirst', 'true'),
    ('event.minor.dataMax', '4');
