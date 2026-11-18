package com.seah.startupsim.board;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardPostService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final BoardPostRepository posts;

    public BoardPostService(BoardPostRepository posts) {
        this.posts = posts;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<PostListItem> list(PostSearchQuery query) {
        return posts.list(query);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<MyPostListItem> mine(PostSearchQuery query, long userId) {
        return posts.mine(query, userId);
    }

    @Transactional(readOnly = true)
    public PostDetail anonymousDetail(long postId) {
        requireId(postId);
        return detail(postId, null);
    }

    @Transactional
    public PostDetail authenticatedDetail(long postId, long userId) {
        requireId(postId);
        // Lock before writing so a concurrent hide/delete cannot make this view count a private post.
        if (!posts.lockActive(postId)) throw BoardException.notFound();
        BoardWrites.safe(() -> {
            posts.countView(postId, userId, LocalDate.now(SEOUL));
            return null;
        });
        return detail(postId, userId);
    }

    @Transactional
    public PostDetail create(long userId, CreatePostRequest request) {
        rejectNul(request.title(), request.content());
        if (request.category() == PostCategory.RECORD) {
            throw BoardException.invalid("엔딩 인증글은 아직 작성할 수 없습니다.");
        }
        long id = BoardWrites.safe(() -> posts.create(userId, request));
        return detail(id, userId);
    }

    @Transactional
    public PostDetail update(long postId, long userId, UpdatePostRequest request) {
        requireId(postId);
        rejectNul(request.title(), request.content());
        var state = ownedActive(postId, userId);
        if ((state.category() == PostCategory.RECORD) != (request.category() == PostCategory.RECORD)) {
            throw BoardException.invalid("엔딩 인증글 여부는 변경할 수 없습니다.");
        }
        requireVersion(state, request.version());
        if (BoardWrites.safe(() -> posts.update(postId, request, state.category())) == 0) {
            recheckFailedWrite(postId, userId, request.version());
        }
        return detail(postId, userId);
    }

    @Transactional
    public void delete(long postId, long userId, long version) {
        requireId(postId);
        if (version < 0) throw BoardException.invalid();
        requireVersion(ownedActive(postId, userId), version);
        if (BoardWrites.safe(() -> posts.delete(postId, version)) == 0) {
            recheckFailedWrite(postId, userId, version);
        }
    }

    private PostDetail detail(long postId, Long userId) {
        return posts.publicDetail(postId, userId).orElseThrow(BoardException::notFound);
    }

    private BoardPostRepository.EditState ownedActive(long postId, long userId) {
        var state = posts.editState(postId).orElseThrow(BoardException::notFound);
        if (state.authorId() != userId) throw BoardException.forbidden();
        return state;
    }

    private void recheckFailedWrite(long postId, long userId, long version) {
        requireVersion(ownedActive(postId, userId), version);
        throw BoardException.versionConflict();
    }

    private static void requireVersion(BoardPostRepository.EditState state, long expectedVersion) {
        if (state.version() != expectedVersion) throw BoardException.versionConflict();
    }

    private static void requireId(long postId) {
        if (postId < 1) throw BoardException.invalid();
    }

    private static void rejectNul(String title, String content) {
        if (title.indexOf(0) >= 0 || content.indexOf(0) >= 0) throw BoardException.invalid();
    }
}
