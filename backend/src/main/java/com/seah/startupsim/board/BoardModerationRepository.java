package com.seah.startupsim.board;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BoardModerationRepository {
    enum TargetType { POST, COMMENT }
    enum State { ACTIVE, HIDDEN, DELETED }
    enum ReportStatus { PENDING, RESOLVED, DISMISSED }
    record Target(long postId, long authorId, State status, boolean pinned) {}
    private final JdbcTemplate jdbc;

    BoardModerationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Target lockTarget(TargetType type, long id, boolean publicOnly) {
        long postId = type == TargetType.POST ? id : jdbc.query("""
                SELECT post_id FROM comments WHERE id = ?
                """, (rs, n) -> rs.getLong("post_id"), id).stream().findFirst().orElseThrow(BoardException::notFound);
        var post = jdbc.query("""
                SELECT id, author_id, status, is_pinned FROM posts WHERE id = ? FOR UPDATE
                """, (rs, n) -> new Target(rs.getLong("id"), rs.getLong("author_id"),
                State.valueOf(rs.getString("status")), rs.getBoolean("is_pinned")), postId)
                .stream().findFirst().orElseThrow(BoardException::notFound);
        if (publicOnly && post.status() != State.ACTIVE) throw BoardException.notFound();
        if (type == TargetType.POST) return post;
        var comment = jdbc.query("""
                SELECT post_id, author_id, status FROM comments WHERE id = ? AND post_id = ? FOR UPDATE
                """, (rs, n) -> new Target(rs.getLong("post_id"), rs.getLong("author_id"),
                State.valueOf(rs.getString("status")), false), id, postId)
                .stream().findFirst().orElseThrow(BoardException::notFound);
        if (publicOnly && comment.status() != State.ACTIVE) throw BoardException.notFound();
        return comment;
    }

    boolean hasReport(TargetType type, long id, long reporter) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM post_reports WHERE target_type = ? AND target_id = ? AND reporter_id = ?)
                """, Boolean.class, type.name(), id, reporter));
    }

    long report(TargetType type, long id, long reporter, String reason, String detail) {
        return jdbc.queryForObject("""
                INSERT INTO post_reports (target_type, target_id, reporter_id, reason, detail)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, type.name(), id, reporter, reason, detail);
    }
    record Report(long id, TargetType type, long targetId, String reason, String detail,
                  ReportStatus status, String handledBy) {}
    record Post(long id, String title, String content, String category, State status, boolean pinned) {}
    record Comment(long id, Long parentId, String content, State status) {}

    java.util.List<Report> reports(ReportStatus status, int page) {
        return jdbc.query("""
                SELECT id, target_type, target_id, reason, detail, status, handled_by
                FROM post_reports WHERE status = ? ORDER BY id DESC LIMIT 21 OFFSET ?
                """, this::mapReport, status.name(), offset(page));
    }

    Report report(long id) {
        return jdbc.query("""
                SELECT id, target_type, target_id, reason, detail, status, handled_by
                FROM post_reports WHERE id = ?
                """, this::mapReport, id).stream().findFirst().orElseThrow(BoardException::notFound);
    }

    private Report mapReport(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Report(rs.getLong("id"), TargetType.valueOf(rs.getString("target_type")), rs.getLong("target_id"),
                rs.getString("reason"), rs.getString("detail"), ReportStatus.valueOf(rs.getString("status")),
                rs.getString("handled_by"));
    }

    void handle(Report report, ReportStatus status, String principal) {
        var pending = jdbc.queryForList("""
                SELECT id FROM post_reports WHERE target_type = ? AND target_id = ? AND status = 'PENDING'
                ORDER BY id FOR UPDATE
                """, Long.class, report.type().name(), report.targetId());
        for (long id : pending) {
            jdbc.update("""
                    UPDATE post_reports SET status = ?, handled_at = now(), handled_by = ? WHERE id = ?
                    """, status.name(), principal, id);
        }
    }

    java.util.List<Post> posts(State state, PostCategory category, String search, int page) {
        return jdbc.query("""
                SELECT id, title, content, category, status, is_pinned FROM posts
                WHERE (CAST(? AS text) IS NULL OR status = ?) AND (CAST(? AS text) IS NULL OR category = ?)
                  AND (strpos(lower(title), lower(?)) > 0 OR strpos(lower(content), lower(?)) > 0 OR ? = '')
                ORDER BY id DESC LIMIT 21 OFFSET ?
                """, this::mapPost, state == null ? null : state.name(), state == null ? null : state.name(),
                category == null ? null : category.name(), category == null ? null : category.name(), search, search, search, offset(page));
    }

    Post post(long id) {
        return jdbc.query("""
                SELECT id, title, content, category, status, is_pinned FROM posts WHERE id = ?
                """, this::mapPost, id).stream().findFirst().orElseThrow(BoardException::notFound);
    }

    private Post mapPost(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Post(rs.getLong("id"), rs.getString("title"), rs.getString("content"), rs.getString("category"),
                State.valueOf(rs.getString("status")), rs.getBoolean("is_pinned"));
    }

    java.util.List<Comment> comments(long postId, int page) {
        return jdbc.query("""
                SELECT id, parent_id, content, status FROM comments WHERE post_id = ?
                ORDER BY coalesce(parent_id, id), id LIMIT 21 OFFSET ?
                """, (rs, n) -> new Comment(rs.getLong("id"), rs.getObject("parent_id", Long.class),
                rs.getString("content"), State.valueOf(rs.getString("status"))), postId, offset(page));
    }

    Comment comment(long id) {
        return jdbc.query("SELECT id, parent_id, content, status FROM comments WHERE id = ?",
                (rs, n) -> new Comment(rs.getLong("id"), rs.getObject("parent_id", Long.class),
                        rs.getString("content"), State.valueOf(rs.getString("status"))), id)
                .stream().findFirst().orElseThrow(BoardException::notFound);
    }

    private long offset(int page) {
        if (page < 0) throw BoardException.invalid();
        return (long) page * 20;
    }

    long commentPostId(long id) {
        return jdbc.queryForList("SELECT post_id FROM comments WHERE id = ?", Long.class, id)
                .stream().findFirst().orElseThrow(BoardException::notFound);
    }

    void pin(long id, boolean pinned) {
        jdbc.update("UPDATE posts SET is_pinned = ?, version = version + 1, updated_at = now() WHERE id = ?", pinned, id);
    }

    void change(TargetType type, long id, long postId, State status) {
        String sql = type == TargetType.POST ? """
                UPDATE posts SET status = ?, hidden_at = CASE WHEN ? = 'HIDDEN' THEN now() ELSE NULL END,
                deleted_at = CASE WHEN ? = 'DELETED' THEN now() ELSE NULL END,
                version = version + 1, updated_at = now() WHERE id = ?
                """ : """
                UPDATE comments SET status = ?, hidden_at = CASE WHEN ? = 'HIDDEN' THEN now() ELSE NULL END,
                deleted_at = CASE WHEN ? = 'DELETED' THEN now() ELSE NULL END,
                version = version + 1, updated_at = now() WHERE id = ?
                """;
        jdbc.update(sql, status.name(), status.name(), status.name(), id);
        if (type == TargetType.COMMENT) {
            jdbc.update("""
                    UPDATE posts SET comment_count = (SELECT count(*) FROM comments WHERE post_id = ? AND status = 'ACTIVE')
                    WHERE id = ?
                    """, postId, postId);
            jdbc.update("""
                    UPDATE comments parent SET reply_count =
                        (SELECT count(*) FROM comments r WHERE r.parent_id = parent.id AND r.status = 'ACTIVE')
                    WHERE parent.id = (SELECT parent_id FROM comments WHERE id = ?)
                    """, id);
        }
    }
}
