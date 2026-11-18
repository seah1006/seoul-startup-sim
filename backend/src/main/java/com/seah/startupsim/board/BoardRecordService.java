package com.seah.startupsim.board;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RECORD 인증글 작성 판정(404 → 409 → 스냅샷)과 공개 연결 조회. */
@Service
public class BoardRecordService {
    private final BoardRecordReadRepository records;
    private final BoardPostRepository posts;

    public BoardRecordService(BoardRecordReadRepository records, BoardPostRepository posts) {
        this.records = records;
        this.posts = posts;
    }

    /** 호출자의 트랜잭션에서 실행된다. 요청의 스냅샷 값은 받지 않고 서버 원본에서만 복사한다. */
    long create(long userId, long gameId, String title, String content) {
        var game = records.game(gameId).filter(g -> g.userId() == userId).orElseThrow(BoardException::notFound);
        if (!"ENDED".equals(game.status())) {
            throw new BoardException(HttpStatus.CONFLICT, "RECORD_GAME_NOT_ENDED", "종료된 게임만 인증글로 올릴 수 있습니다.");
        }
        RecordSnapshot snapshot = records.snapshot(gameId).orElseThrow(() ->
                new BoardException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "엔딩 기록을 찾을 수 없습니다."));
        return BoardWrites.safe(() -> posts.createRecord(userId, title, content, snapshot));
    }

    @Transactional(readOnly = true)
    public Optional<RecordPostLink> publicLink(long gameId) {
        return gameId < 1 ? Optional.empty() : records.publicLink(gameId);
    }
}
