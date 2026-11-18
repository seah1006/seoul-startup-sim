package com.seah.startupsim.board;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardCommentService {
    private final BoardPostRepository posts;
    private final BoardCommentRepository comments;

    public BoardCommentService(BoardPostRepository posts, BoardCommentRepository comments) {
        this.posts = posts;
        this.comments = comments;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<CommentItem> list(long postId, Long parentId, Long userId, Integer page, Integer size) {
        requireId(postId);
        if (parentId != null) requireId(parentId);
        var pagination = BoardPagination.of(page, size);
        if (!posts.isActive(postId) || (parentId != null && !comments.visibleRoot(postId, parentId))) {
            throw BoardException.notFound();
        }
        return comments.list(postId, parentId, userId, pagination);
    }

    @Transactional
    public CommentItem create(long postId, long userId, CreateCommentRequest request) {
        requireId(postId);
        requireContent(request.content());
        Long parentId = request.parentId();
        if (parentId != null) requireId(parentId);
        lockPost(postId);
        if (parentId != null) {
            var parent = comments.lockActive(postId, parentId).orElseThrow(BoardException::notFound);
            if (parent.parentId() != null) {
                throw new BoardException(HttpStatus.BAD_REQUEST, "COMMENT_DEPTH_EXCEEDED", "대댓글에는 답글을 달 수 없습니다.");
            }
        }
        return BoardWrites.safe(() -> {
            long commentId = comments.create(postId, userId, request);
            posts.incrementComments(postId);
            if (parentId != null) comments.incrementReplies(postId, parentId);
            return item(postId, commentId, userId);
        });
    }

    @Transactional
    public CommentItem update(long postId, long commentId, long userId, UpdateCommentRequest request) {
        requireId(postId);
        requireId(commentId);
        requireContent(request.content());
        lockPost(postId);
        requireVersion(ownedActive(postId, commentId, userId), request.version());
        if (BoardWrites.safe(() -> comments.update(postId, commentId, request)) == 0) {
            recheckFailedWrite(postId, commentId, userId, request.version());
        }
        return item(postId, commentId, userId);
    }

    @Transactional
    public void delete(long postId, long commentId, long userId, long version) {
        requireId(postId);
        requireId(commentId);
        if (version < 0) throw BoardException.invalid();
        lockPost(postId);
        var state = ownedActive(postId, commentId, userId);
        requireVersion(state, version);
        BoardWrites.safe(() -> {
            if (comments.delete(postId, commentId, version) == 0) {
                recheckFailedWrite(postId, commentId, userId, version);
            }
            posts.decrementComments(postId);
            if (state.parentId() != null) comments.decrementReplies(postId, state.parentId());
            return null;
        });
    }

    private void lockPost(long postId) {
        if (!posts.lockActive(postId)) throw BoardException.notFound();
    }

    private BoardCommentRepository.EditState ownedActive(long postId, long commentId, long userId) {
        var state = comments.active(postId, commentId).orElseThrow(BoardException::notFound);
        if (state.authorId() != userId) throw BoardException.forbidden();
        return state;
    }

    private void recheckFailedWrite(long postId, long commentId, long userId, long version) {
        requireVersion(ownedActive(postId, commentId, userId), version);
        throw versionConflict();
    }

    private CommentItem item(long postId, long commentId, long userId) {
        return comments.find(postId, commentId, userId).orElseThrow(BoardException::notFound);
    }

    private static void requireVersion(BoardCommentRepository.EditState state, long version) {
        if (state.version() != version) throw versionConflict();
    }

    private static BoardException versionConflict() {
        return new BoardException(HttpStatus.CONFLICT, "COMMENT_VERSION_CONFLICT", "댓글이 변경되었습니다. 다시 조회해 주세요.");
    }

    private static void requireId(long id) {
        if (id < 1) throw BoardException.invalid();
    }

    private static void requireContent(String content) {
        if (content.indexOf(0) >= 0) throw BoardException.invalid();
    }
}
