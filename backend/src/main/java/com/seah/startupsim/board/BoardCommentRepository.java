package com.seah.startupsim.board;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BoardCommentRepository {
    private static final String ITEM = """
            SELECT c.id, c.parent_id, c.author_id, u.nickname, c.content, c.created_at,
                   c.updated_at, c.version, c.reply_count, c.status
            FROM comments c JOIN users u ON u.id = c.author_id
            """;
    private static final String ROOTS = """
            c.parent_id IS NULL AND (c.status = 'ACTIVE' OR c.reply_count > 0)
            """;
    private static final String REPLIES = "c.parent_id = ? AND c.status = 'ACTIVE'";
    private final JdbcTemplate jdbc;

    public BoardCommentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    PageResponse<CommentItem> list(long postId, Long parentId, Long userId, PageRequest page) {
        String where = " WHERE c.post_id = ? AND " + (parentId == null ? ROOTS : REPLIES);
        Object[] countArgs = parentId == null ? new Object[]{postId} : new Object[]{postId, parentId};
        Object[] pageArgs = parentId == null ? new Object[]{postId, page.getPageSize(), page.getOffset()}
                : new Object[]{postId, parentId, page.getPageSize(), page.getOffset()};
        long total = jdbc.queryForObject("SELECT count(*) FROM comments c" + where, Long.class, countArgs);
        var items = jdbc.query(ITEM + where + " ORDER BY c.created_at, c.id LIMIT ? OFFSET ?",
                (rs, n) -> item(rs, userId), pageArgs);
        return PageResponse.of(items, page, total);
    }

    boolean visibleRoot(long postId, long commentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM comments c"
                + " WHERE c.post_id = ? AND c.id = ? AND " + ROOTS + ")", Boolean.class, postId, commentId));
    }

    Optional<EditState> active(long postId, long commentId) {
        return jdbc.query("""
                SELECT parent_id, author_id, version FROM comments
                WHERE post_id = ? AND id = ? AND status = 'ACTIVE'
                """, (rs, n) -> new EditState(rs.getObject("parent_id", Long.class),
                rs.getLong("author_id"), rs.getLong("version")), postId, commentId).stream().findFirst();
    }

    Optional<EditState> lockActive(long postId, long commentId) {
        // Compatible with the V11 trigger's FOR KEY SHARE; prevents a parent hide before insertion.
        return jdbc.query("""
                SELECT parent_id, author_id, version FROM comments
                WHERE post_id = ? AND id = ? AND status = 'ACTIVE' FOR NO KEY UPDATE
                """, (rs, n) -> new EditState(rs.getObject("parent_id", Long.class),
                rs.getLong("author_id"), rs.getLong("version")), postId, commentId).stream().findFirst();
    }

    long create(long postId, long userId, CreateCommentRequest request) {
        return jdbc.queryForObject("""
                INSERT INTO comments (post_id, parent_id, author_id, content) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, postId, request.parentId(), userId, request.content());
    }

    Optional<CommentItem> find(long postId, long commentId, long userId) {
        return jdbc.query(ITEM + " WHERE c.post_id = ? AND c.id = ? AND c.status = 'ACTIVE'",
                (rs, n) -> item(rs, userId), postId, commentId).stream().findFirst();
    }

    int update(long postId, long commentId, UpdateCommentRequest request) {
        return jdbc.update("""
                UPDATE comments SET content = ?, version = version + 1, updated_at = now()
                WHERE post_id = ? AND id = ? AND version = ? AND status = 'ACTIVE'
                """, request.content(), postId, commentId, request.version());
    }

    int delete(long postId, long commentId, long version) {
        return jdbc.update("""
                UPDATE comments SET status = 'DELETED', deleted_at = now(), updated_at = now(), version = version + 1
                WHERE post_id = ? AND id = ? AND version = ? AND status = 'ACTIVE'
                """, postId, commentId, version);
    }

    void incrementReplies(long postId, long parentId) {
        jdbc.update("UPDATE comments SET reply_count = reply_count + 1 WHERE post_id = ? AND id = ?",
                postId, parentId);
    }

    void decrementReplies(long postId, long parentId) {
        jdbc.update("""
                UPDATE comments SET reply_count = reply_count - 1 WHERE post_id = ? AND id = ? AND reply_count > 0
                """, postId, parentId);
    }

    private static CommentItem item(ResultSet rs, Long userId) throws SQLException {
        boolean placeholder = !"ACTIVE".equals(rs.getString("status"));
        return new CommentItem(rs.getLong("id"), rs.getObject("parent_id", Long.class),
                placeholder ? null : rs.getString("nickname"),
                placeholder ? "표시할 수 없는 댓글입니다." : rs.getString("content"),
                rs.getTimestamp("created_at").toInstant(), placeholder ? null : rs.getTimestamp("updated_at").toInstant(),
                placeholder ? null : rs.getLong("version"), rs.getInt("reply_count"),
                !placeholder && userId != null && userId == rs.getLong("author_id"), placeholder);
    }

    record EditState(Long parentId, long authorId, long version) {}
}
