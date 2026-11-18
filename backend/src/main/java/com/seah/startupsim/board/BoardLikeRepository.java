package com.seah.startupsim.board;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BoardLikeRepository {
    private final JdbcTemplate jdbc;

    public BoardLikeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int insert(long postId, long userId) {
        return jdbc.update("""
                INSERT INTO post_likes (post_id, user_id) VALUES (?, ?)
                ON CONFLICT (post_id, user_id) DO NOTHING
                """, postId, userId);
    }

    int delete(long postId, long userId) {
        return jdbc.update("DELETE FROM post_likes WHERE post_id = ? AND user_id = ?", postId, userId);
    }

    void increment(long postId) {
        jdbc.update("UPDATE posts SET like_count = like_count + 1 WHERE id = ?", postId);
    }

    void decrement(long postId) {
        jdbc.update("UPDATE posts SET like_count = like_count - 1 WHERE id = ? AND like_count > 0", postId);
    }
}
