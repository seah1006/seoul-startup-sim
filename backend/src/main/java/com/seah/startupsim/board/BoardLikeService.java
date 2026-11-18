package com.seah.startupsim.board;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardLikeService {
    private final BoardPostRepository posts;
    private final BoardLikeRepository likes;

    public BoardLikeService(BoardPostRepository posts, BoardLikeRepository likes) {
        this.posts = posts;
        this.likes = likes;
    }

    @Transactional
    public void set(long postId, long userId, boolean liked) {
        if (postId < 1) throw BoardException.invalid();
        if (!posts.lockActive(postId)) throw BoardException.notFound();
        BoardWrites.safe(() -> {
            if (liked) {
                if (likes.insert(postId, userId) == 1) likes.increment(postId);
            } else {
                if (likes.delete(postId, userId) == 1) likes.decrement(postId);
            }
            return null;
        });
    }
}
