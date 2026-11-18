package com.seah.startupsim.board;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BoardPostRepository {
    private static final String LIST_COLUMNS = """
            p.id, p.category, p.title, u.nickname, p.created_at, p.updated_at,
            p.view_count, p.like_count, p.comment_count, p.is_pinned,
            p.game_id, p.ending_type, p.service_code, p.service_name, p.area_code, p.area_name,
            p.survived_quarters, p.total_profit, p.shop_name, p.owner_name
            """;
    private static final String FROM = " FROM posts p JOIN users u ON u.id = p.author_id ";
    private static final String LIKE = " ILIKE ? ESCAPE '\\'";
    private final JdbcTemplate jdbc;

    public BoardPostRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    PageResponse<PostListItem> list(PostSearchQuery query) {
        Filter filter = filter(query, null);
        long total = count(filter);
        List<PostListItem> content = query.page().getOffset() >= total ? List.of()
                : jdbc.query("SELECT " + LIST_COLUMNS + FROM + filter.sql() + orderAndPage(query),
                        (rs, n) -> summary(rs).listItem(), pageParameters(query, filter));
        return PageResponse.of(content, query.page(), total);
    }

    PageResponse<MyPostListItem> mine(PostSearchQuery query, long userId) {
        Filter filter = filter(query, userId);
        long total = count(filter);
        List<MyPostListItem> content = query.page().getOffset() >= total ? List.of()
                : jdbc.query("SELECT " + LIST_COLUMNS + ", p.status, p.version" + FROM + filter.sql()
                        + orderAndPage(query), (rs, n) -> summary(rs).mine(rs), pageParameters(query, filter));
        return PageResponse.of(content, query.page(), total);
    }

    private long count(Filter filter) {
        return jdbc.queryForObject("SELECT count(*)" + FROM + filter.sql(), Long.class, filter.args().toArray());
    }

    private static Filter filter(PostSearchQuery query, Long userId) {
        StringBuilder sql = new StringBuilder(userId == null ? " WHERE p.status = 'ACTIVE'"
                : " WHERE p.status IN ('ACTIVE', 'HIDDEN') AND p.author_id = ?");
        List<Object> args = new ArrayList<>();
        if (userId != null) args.add(userId);
        if (query.category() == PostCategory.RECORD) {
            sql.append(" AND p.category = 'RECORD'");
        } else if (query.category() != null) {
            sql.append(" AND p.category = ?");
            args.add(query.category().name());
        }
        if (query.serviceCode() != null) {
            sql.append(" AND p.service_code = ?");
            args.add(query.serviceCode());
        }
        if (query.areaCode() != null) {
            sql.append(" AND p.area_code = ?");
            args.add(query.areaCode());
        }
        if (query.q() != null) {
            String pattern = "%" + query.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND ").append(switch (query.searchType()) {
                case title -> "p.title" + LIKE;
                case content -> "p.content" + LIKE;
                case author -> "u.nickname" + LIKE;
                case titleContent -> "(p.title" + LIKE + " OR p.content" + LIKE + ")";
            });
            args.add(pattern);
            if (query.searchType() == PostSearchQuery.SearchType.titleContent) args.add(pattern);
        }
        return new Filter(sql.toString(), args);
    }

    private static String orderAndPage(PostSearchQuery query) {
        return " ORDER BY p.is_pinned DESC, " + query.sort().sql + ", p.id DESC LIMIT ? OFFSET ?";
    }

    private static Object[] pageParameters(PostSearchQuery query, Filter filter) {
        List<Object> parameters = new ArrayList<>(filter.args());
        parameters.add(query.page().getPageSize());
        parameters.add(query.page().getOffset());
        return parameters.toArray();
    }

    Optional<PostDetail> publicDetail(long postId, Long userId) {
        return jdbc.query("SELECT " + LIST_COLUMNS + """
                , p.content, p.version, p.author_id,
                EXISTS (SELECT 1 FROM post_likes l WHERE l.post_id = p.id AND l.user_id = ?) AS liked_by_me
                """ + FROM + " WHERE p.id = ? AND p.status = 'ACTIVE'",
                (rs, n) -> summary(rs).detail(rs, userId), userId, postId).stream().findFirst();
    }

    boolean isActive(long postId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM posts WHERE id = ? AND status = 'ACTIVE')
                """, Boolean.class, postId));
    }

    void incrementComments(long postId) {
        jdbc.update("UPDATE posts SET comment_count = comment_count + 1 WHERE id = ?", postId);
    }

    void decrementComments(long postId) {
        jdbc.update("UPDATE posts SET comment_count = comment_count - 1 WHERE id = ? AND comment_count > 0", postId);
    }

    // Post first: serialize counter writes with moderation and avoid FK lock upgrades.
    boolean lockActive(long postId) {
        return !jdbc.queryForList("""
                SELECT id FROM posts WHERE id = ? AND status = 'ACTIVE' FOR NO KEY UPDATE
                """, Long.class, postId).isEmpty();
    }

    void countView(long postId, long userId, LocalDate viewedOn) {
        int inserted = jdbc.update("""
                INSERT INTO post_views (post_id, user_id, viewed_on) VALUES (?, ?, ?)
                ON CONFLICT (post_id, user_id, viewed_on) DO NOTHING
                """, postId, userId, viewedOn);
        if (inserted == 1) {
            if (jdbc.update("UPDATE posts SET view_count = view_count + 1 WHERE id = ? AND status = 'ACTIVE'", postId) == 0) {
                throw BoardException.notFound();
            }
        }
    }

    long create(long userId, CreatePostRequest request) {
        return jdbc.queryForObject("""
                INSERT INTO posts (category, title, content, author_id) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, request.category().name(), request.title(), request.content(), userId);
    }

    long createRecord(long userId, String title, String content, RecordSnapshot s) {
        return jdbc.queryForObject("""
                INSERT INTO posts (category, title, content, author_id, game_id, ending_type, service_code, service_name,
                    area_code, area_name, survived_quarters, total_profit, shop_name, owner_name)
                VALUES ('RECORD', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, title, content, userId, s.gameId(), s.endingType(), s.serviceCode(), s.serviceName(),
                s.areaCode(), s.areaName(), s.survivedQuarters(), s.totalProfit(), s.shopName(), s.ownerName());
    }

    Optional<EditState> editState(long postId) {
        return jdbc.query("""
                SELECT author_id, category, version FROM posts WHERE id = ? AND status = 'ACTIVE'
                """, (rs, n) -> new EditState(rs.getLong("author_id"),
                PostCategory.valueOf(rs.getString("category")), rs.getLong("version")), postId).stream().findFirst();
    }

    int update(long postId, UpdatePostRequest request, PostCategory currentCategory) {
        // Do not fire the RECORD attachment trigger on edits to a RECORD title/body.
        if (currentCategory == PostCategory.RECORD) {
            return jdbc.update("""
                    UPDATE posts SET title = ?, content = ?, version = version + 1, updated_at = now()
                    WHERE id = ? AND version = ? AND status = 'ACTIVE'
                    """, request.title(), request.content(), postId, request.version());
        }
        return jdbc.update("""
                UPDATE posts SET category = ?, title = ?, content = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND version = ? AND status = 'ACTIVE'
                """, request.category().name(), request.title(), request.content(), postId, request.version());
    }

    int delete(long postId, long version) {
        return jdbc.update("""
                UPDATE posts SET status = 'DELETED', deleted_at = now(), is_pinned = false,
                    version = version + 1, updated_at = now()
                WHERE id = ? AND version = ? AND status = 'ACTIVE'
                """, postId, version);
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        PostCategory category = PostCategory.valueOf(rs.getString("category"));
        RecordSnapshot record = null;
        if (category == PostCategory.RECORD) {
            long gameId = rs.getLong("game_id");
            record = new RecordSnapshot(gameId, rs.getString("ending_type"), rs.getString("service_code"),
                    rs.getString("service_name"), rs.getString("area_code"), rs.getString("area_name"),
                    rs.getInt("survived_quarters"), rs.getLong("total_profit"), rs.getString("shop_name"),
                    rs.getString("owner_name"), "/share/" + gameId);
        }
        return new Summary(rs.getLong("id"), category, rs.getString("title"), rs.getString("nickname"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getLong("view_count"),
                rs.getInt("like_count"), rs.getInt("comment_count"), rs.getBoolean("is_pinned"), record);
    }

    private record Filter(String sql, List<Object> args) {}
    record EditState(long authorId, PostCategory category, long version) {}

    private record Summary(long id, PostCategory category, String title, String nickname, Instant createdAt,
            Instant updatedAt, long views, int likes, int comments, boolean pinned, RecordSnapshot record) {
        PostListItem listItem() {
            return new PostListItem(id, category, title, nickname, createdAt, updatedAt, views, likes, comments, pinned, record);
        }

        MyPostListItem mine(ResultSet rs) throws SQLException {
            return new MyPostListItem(id, category, title, nickname, createdAt, updatedAt, views, likes, comments,
                    pinned, record, rs.getString("status"), rs.getLong("version"));
        }

        PostDetail detail(ResultSet rs, Long userId) throws SQLException {
            return new PostDetail(id, category, title, nickname, createdAt, updatedAt, views, likes, comments,
                    pinned, record, rs.getString("content"), rs.getLong("version"), rs.getBoolean("liked_by_me"),
                    userId != null && userId == rs.getLong("author_id"));
        }
    }
}

