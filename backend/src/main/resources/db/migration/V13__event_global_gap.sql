-- 전환점 발생 다음 1분기는 어떤 전환점도 발생하지 않는다. 게임 생성 시 설정 스냅샷에 고정.
INSERT INTO game_settings (key, value) VALUES ('event.fire.globalGap', '1');
