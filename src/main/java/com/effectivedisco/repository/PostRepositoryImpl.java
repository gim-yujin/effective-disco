package com.effectivedisco.repository;

import com.effectivedisco.domain.Board;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Parameter;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Repository
@Transactional(readOnly = true)
public class PostRepositoryImpl implements PostRepositoryCustom {

    private static final String POSTGRES_FTS_POST_ID_BRANCH = """
            SELECT p.id
            FROM posts p
            WHERE p.draft = false
              %s
              AND to_tsvector('simple', coalesce(p.title, '') || ' ' || coalesce(p.content, ''))
                    @@ plainto_tsquery('simple', :keyword)
            """;
    private static final String POSTGRES_USERNAME_POST_ID_BRANCH = """
            SELECT p.id
            FROM users a
            JOIN posts p ON p.user_id = a.id
            WHERE p.draft = false
              %s
              AND lower(a.username) LIKE ('%%' || lower(:keyword) || '%%')
            """;

    private static final String POSTGRES_GLOBAL_KEYWORD_SQL = createPostgresKeywordContentSql(false);
    private static final String POSTGRES_GLOBAL_KEYWORD_COUNT_SQL = createPostgresKeywordCountSql(false);
    private static final String POSTGRES_GLOBAL_KEYWORD_SLICE_SQL = createPostgresKeywordSliceSql(false);
    private static final String POSTGRES_BOARD_KEYWORD_SQL = createPostgresBoardKeywordContentSql();
    private static final String POSTGRES_BOARD_KEYWORD_COUNT_SQL = createPostgresKeywordCountSql(true);
    private static final String POSTGRES_BOARD_KEYWORD_SLICE_SQL = createPostgresBoardKeywordSliceSql();
    private static final String FALLBACK_GLOBAL_KEYWORD_SQL = createFallbackKeywordContentSql(false);
    private static final String FALLBACK_GLOBAL_KEYWORD_SLICE_SQL = createFallbackKeywordSliceSql(false);

    private static final String FALLBACK_GLOBAL_KEYWORD_COUNT_SQL = """
            SELECT COUNT(*)
            FROM posts p
            JOIN users a ON a.id = p.user_id
            WHERE p.draft = false
              AND (
                lower(p.title) LIKE concat('%%', lower(:keyword), '%%')
                OR lower(p.content) LIKE concat('%%', lower(:keyword), '%%')
                OR lower(a.username) LIKE concat('%%', lower(:keyword), '%%')
              )
            """;
    private static final String FALLBACK_BOARD_KEYWORD_SQL = createFallbackKeywordContentSql(true);
    private static final String FALLBACK_BOARD_KEYWORD_SLICE_SQL = createFallbackKeywordSliceSql(true);
    private static final String FALLBACK_BOARD_KEYWORD_COUNT_SQL = """
            SELECT COUNT(*)
            FROM posts p
            JOIN users a ON a.id = p.user_id
            WHERE p.board_id = :boardId
              AND p.draft = false
              AND (
                lower(p.title) LIKE concat('%%', lower(:keyword), '%%')
                OR lower(p.content) LIKE concat('%%', lower(:keyword), '%%')
                OR lower(a.username) LIKE concat('%%', lower(:keyword), '%%')
              )
            """;

    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private volatile Boolean postgresDatabase;

    public PostRepositoryImpl(EntityManager entityManager, JdbcTemplate jdbcTemplate) {
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    private static String createPostgresKeywordContentSql(boolean boardSearch) {
        String boardPredicate = boardSearch ? "AND p.board_id = :boardId" : "";
        String boardJoin = boardSearch ? "" : "LEFT JOIN boards b ON b.id = p.board_id";
        String boardSelect = boardSearch
                ? ":boardName AS board_name,\n                :boardSlug AS board_slug"
                : "b.name,\n                b.slug";
        String idUnion = """
                WITH matched_post_ids AS (
                    %s
                    UNION
                    %s
                )
                """.formatted(
                POSTGRES_FTS_POST_ID_BRANCH.formatted(boardPredicate),
                POSTGRES_USERNAME_POST_ID_BRANCH.formatted(boardPredicate)
        );
        return idUnion + """
                SELECT
                    p.id,
                    p.title,
                    '' AS content,
                    p.created_at,
                    p.updated_at,
                    p.comment_count,
                    p.like_count,
                    p.view_count,
                    p.pinned,
                    p.draft,
                    p.image_url,
                    a.username,
                    %s
                FROM matched_post_ids m
                JOIN posts p ON p.id = m.id
                JOIN users a ON a.id = p.user_id
                %s
                ORDER BY p.created_at DESC, p.id DESC
                """.formatted(boardSelect, boardJoin);
    }

    private static String createPostgresBoardKeywordContentSql() {
        String idUnion = """
                WITH matched_post_ids AS (
                    %s
                    UNION
                    %s
                ),
                ordered_post_window AS (
                    SELECT p.id, p.created_at
                    FROM matched_post_ids m
                    JOIN posts p ON p.id = m.id
                    ORDER BY p.created_at DESC, p.id DESC
                    OFFSET :offset
                    LIMIT :limit
                )
                """.formatted(
                POSTGRES_FTS_POST_ID_BRANCH.formatted("AND p.board_id = :boardId"),
                POSTGRES_USERNAME_POST_ID_BRANCH.formatted("AND p.board_id = :boardId")
        );
        return idUnion + """
                SELECT
                    p.id,
                    p.title,
                    '' AS content,
                    p.created_at,
                    p.updated_at,
                    p.comment_count,
                    p.like_count,
                    p.view_count,
                    p.pinned,
                    p.draft,
                    p.image_url,
                    a.username,
                    :boardName AS board_name,
                    :boardSlug AS board_slug
                FROM ordered_post_window w
                JOIN posts p ON p.id = w.id
                JOIN users a ON a.id = p.user_id
                ORDER BY w.created_at DESC, w.id DESC
                """;
    }

    private static String createPostgresKeywordCountSql(boolean boardSearch) {
        String boardPredicate = boardSearch ? "AND p.board_id = :boardId" : "";
        String idUnion = """
                WITH matched_post_ids AS (
                    %s
                    UNION
                    %s
                )
                """.formatted(
                POSTGRES_FTS_POST_ID_BRANCH.formatted(boardPredicate),
                POSTGRES_USERNAME_POST_ID_BRANCH.formatted(boardPredicate)
        );
        return idUnion + """
                SELECT COUNT(*)
                FROM matched_post_ids
                """;
    }

    private static String createPostgresKeywordSliceSql(boolean boardSearch) {
        String boardPredicate = boardSearch ? "AND p.board_id = :boardId" : "";
        String boardJoin = boardSearch ? "" : "LEFT JOIN boards b ON b.id = p.board_id";
        String boardSelect = boardSearch
                ? ":boardName AS board_name,\n                :boardSlug AS board_slug"
                : "b.name,\n                b.slug";
        String idUnion = """
                WITH matched_post_ids AS (
                    %s
                    UNION
                    %s
                )
                """.formatted(
                POSTGRES_FTS_POST_ID_BRANCH.formatted(boardPredicate),
                POSTGRES_USERNAME_POST_ID_BRANCH.formatted(boardPredicate)
        );
        return idUnion + """
                SELECT
                    p.id,
                    p.title,
                    '' AS content,
                    p.created_at,
                    p.updated_at,
                    p.comment_count,
                    p.like_count,
                    p.view_count,
                    p.pinned,
                    p.draft,
                    p.image_url,
                    a.username,
                    %s
                FROM matched_post_ids m
                JOIN posts p ON p.id = m.id
                JOIN users a ON a.id = p.user_id
                %s
                WHERE (
                    p.created_at < :cursorCreatedAt
                    OR (p.created_at = :cursorCreatedAt AND p.id < :cursorId)
                )
                ORDER BY p.created_at DESC, p.id DESC
                """.formatted(boardSelect, boardJoin);
    }

    private static String createPostgresBoardKeywordSliceSql() {
        String idUnion = """
                WITH matched_post_ids AS (
                    %s
                    UNION
                    %s
                ),
                ordered_post_window AS (
                    SELECT p.id, p.created_at
                    FROM matched_post_ids m
                    JOIN posts p ON p.id = m.id
                    WHERE (
                        p.created_at < :cursorCreatedAt
                        OR (p.created_at = :cursorCreatedAt AND p.id < :cursorId)
                    )
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT :limitPlusOne
                )
                """.formatted(
                POSTGRES_FTS_POST_ID_BRANCH.formatted("AND p.board_id = :boardId"),
                POSTGRES_USERNAME_POST_ID_BRANCH.formatted("AND p.board_id = :boardId")
        );
        return idUnion + """
                SELECT
                    p.id,
                    p.title,
                    '' AS content,
                    p.created_at,
                    p.updated_at,
                    p.comment_count,
                    p.like_count,
                    p.view_count,
                    p.pinned,
                    p.draft,
                    p.image_url,
                    a.username,
                    :boardName AS board_name,
                    :boardSlug AS board_slug
                FROM ordered_post_window w
                JOIN posts p ON p.id = w.id
                JOIN users a ON a.id = p.user_id
                ORDER BY w.created_at DESC, w.id DESC
                """;
    }

    private static String createFallbackKeywordSliceSql(boolean boardSearch) {
        String boardPredicate = boardSearch ? "AND p.board_id = :boardId" : "";
        String boardJoin = boardSearch ? "" : "LEFT JOIN boards b ON b.id = p.board_id";
        String boardSelect = boardSearch
                ? ":boardName AS board_name,\n                    :boardSlug AS board_slug"
                : "b.name,\n                    b.slug";
        return """
                SELECT
                    p.id,
                    p.title,
                    '' AS content,
                    p.created_at,
                    p.updated_at,
                    p.comment_count,
                    p.like_count,
                    p.view_count,
                    p.pinned,
                    p.draft,
                    p.image_url,
                    a.username,
                    %s
                FROM posts p
                JOIN users a ON a.id = p.user_id
                %s
                WHERE p.draft = false
                  %s
                  AND (
                    lower(p.title) LIKE concat('%%', lower(:keyword), '%%')
                    OR lower(p.content) LIKE concat('%%', lower(:keyword), '%%')
                    OR lower(a.username) LIKE concat('%%', lower(:keyword), '%%')
                  )
                  AND (
                    p.created_at < :cursorCreatedAt
                    OR (p.created_at = :cursorCreatedAt AND p.id < :cursorId)
                  )
                ORDER BY p.created_at DESC, p.id DESC
                """.formatted(boardSelect, boardJoin, boardPredicate);
    }

    private static String createFallbackKeywordContentSql(boolean boardSearch) {
        String boardPredicate = boardSearch ? "AND p.board_id = :boardId" : "";
        String boardJoin = boardSearch ? "" : "LEFT JOIN boards b ON b.id = p.board_id";
        String boardSelect = boardSearch
                ? ":boardName AS board_name,\n                :boardSlug AS board_slug"
                : "b.name,\n                b.slug";
        return """
                SELECT
                    p.id,
                    p.title,
                    '' AS content,
                    p.created_at,
                    p.updated_at,
                    p.comment_count,
                    p.like_count,
                    p.view_count,
                    p.pinned,
                    p.draft,
                    p.image_url,
                    a.username,
                    %s
                FROM posts p
                JOIN users a ON a.id = p.user_id
                %s
                WHERE p.draft = false
                  %s
                  AND (
                    lower(p.title) LIKE concat('%%', lower(:keyword), '%%')
                    OR lower(p.content) LIKE concat('%%', lower(:keyword), '%%')
                    OR lower(a.username) LIKE concat('%%', lower(:keyword), '%%')
                  )
                ORDER BY p.created_at DESC
                """.formatted(boardSelect, boardJoin, boardPredicate);
    }

    @Override
    public Page<PostRepository.PostListRow> searchPublicPostListRows(String keyword, Pageable pageable) {
        return executeKeywordSearch(keyword, pageable, null);
    }

    @Override
    public Page<PostRepository.PostListRow> searchPublicPostListRowsInBoard(Board board,
                                                                            String keyword,
                                                                            Pageable pageable) {
        return executeKeywordSearch(keyword, pageable, board);
    }

    @Override
    public Slice<PostRepository.PostListRow> searchPublicPostListRowsSlice(String keyword,
                                                                           Pageable pageable,
                                                                           LocalDateTime cursorCreatedAt,
                                                                           Long cursorId) {
        return executeKeywordSlice(keyword, pageable, null, cursorCreatedAt, cursorId);
    }

    @Override
    public Slice<PostRepository.PostListRow> searchPublicPostListRowsInBoardSlice(Board board,
                                                                                  String keyword,
                                                                                  Pageable pageable,
                                                                                  LocalDateTime cursorCreatedAt,
                                                                                  Long cursorId) {
        return executeKeywordSlice(keyword, pageable, board, cursorCreatedAt, cursorId);
    }

    private Page<PostRepository.PostListRow> executeKeywordSearch(String rawKeyword,
                                                                  Pageable pageable,
                                                                  Board board) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        SearchSqlSet sqlSet = selectSqlSet(board != null, isPostgresDatabase());

        Query contentQuery = entityManager.createNativeQuery(sqlSet.contentSql());
        Query countQuery = entityManager.createNativeQuery(sqlSet.countSql());

        bindParameters(contentQuery, keyword, board);
        bindParameters(countQuery, keyword, board);

        // 문제 해결:
        // 검색 hot path 는 PostgreSQL 에서만 FTS/pg_trgm native SQL 을 써야 하지만,
        // 핵심은 "FTS 조건 OR username LIKE" 를 한 WHERE 절에 섞지 않는 것이다.
        // branch 를 UNION 으로 분리해야 PostgreSQL 이 posts FTS index 와 users trigram index 를
        // 각각 독립적으로 고려할 수 있고, H2 테스트는 기존 substring fallback 으로 유지한다.
        if (board != null && isPostgresDatabase()) {
            // 문제 해결:
            // board-scoped keyword search는 결과 row 수가 작아도 기존 쿼리가 users join을 전체 matched id 집합에 먼저 붙였다.
            // board path에서만 `ordered_post_window` CTE로 현재 page id window를 먼저 자른 뒤 작은 row batch에만 author join을 적용한다.
            int offset = pageable.isPaged() ? (int) pageable.getOffset() : 0;
            int limit = pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE;
            setParameterIfPresent(contentQuery, "offset", offset);
            setParameterIfPresent(contentQuery, "limit", limit);
        } else if (pageable.isPaged()) {
            contentQuery.setFirstResult((int) pageable.getOffset());
            contentQuery.setMaxResults(pageable.getPageSize());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = contentQuery.getResultList();
        List<PostRepository.PostListRow> content = rows.stream()
                .map(this::mapPostListRow)
                .toList();

        Number total = (Number) countQuery.getSingleResult();
        return new PageImpl<>(content, pageable, total.longValue());
    }

    private Slice<PostRepository.PostListRow> executeKeywordSlice(String rawKeyword,
                                                                  Pageable pageable,
                                                                  Board board,
                                                                  LocalDateTime cursorCreatedAt,
                                                                  Long cursorId) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        SearchSqlSet sqlSet = selectSliceSqlSet(board != null, isPostgresDatabase());

        Query contentQuery = entityManager.createNativeQuery(sqlSet.contentSql());
        bindParameters(contentQuery, keyword, board);
        contentQuery.setParameter("cursorCreatedAt", Timestamp.valueOf(cursorCreatedAt));
        contentQuery.setParameter("cursorId", cursorId);
        int batchSize = pageable.isPaged() ? pageable.getPageSize() : 20;
        if (board != null && isPostgresDatabase()) {
            // 문제 해결:
            // board keyword slice도 전체 matched row에 author join을 붙인 뒤 outer maxResults로 잘라내면
            // broad mixed steady-state에서 connection 점유 시간이 늘어난다.
            // board path는 inner window에서 `limit+1` id만 먼저 자르고, projection join은 그 작은 결과에만 적용한다.
            setParameterIfPresent(contentQuery, "limitPlusOne", batchSize + 1);
        } else {
            contentQuery.setMaxResults(batchSize + 1);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = contentQuery.getResultList();
        boolean hasNext = rows.size() > batchSize;
        if (hasNext) {
            rows = rows.subList(0, batchSize);
        }
        List<PostRepository.PostListRow> content = rows.stream()
                .map(this::mapPostListRow)
                .toList();
        return new SliceImpl<>(content, pageable, hasNext);
    }

    private void bindParameters(Query query, String keyword, Board board) {
        query.setParameter("keyword", keyword);
        if (board != null) {
            setParameterIfPresent(query, "boardId", board.getId());
            setParameterIfPresent(query, "boardName", board.getName());
            setParameterIfPresent(query, "boardSlug", board.getSlug());
        }
    }

    private void setParameterIfPresent(Query query, String parameterName, Object value) {
        boolean present = query.getParameters().stream()
                .map(Parameter::getName)
                .anyMatch(parameterName::equals);
        if (present) {
            query.setParameter(parameterName, value);
        }
    }

    private SearchSqlSet selectSqlSet(boolean boardSearch, boolean postgres) {
        if (postgres) {
            return boardSearch
                    ? new SearchSqlSet(POSTGRES_BOARD_KEYWORD_SQL, POSTGRES_BOARD_KEYWORD_COUNT_SQL)
                    : new SearchSqlSet(POSTGRES_GLOBAL_KEYWORD_SQL, POSTGRES_GLOBAL_KEYWORD_COUNT_SQL);
        }
        return boardSearch
                ? new SearchSqlSet(FALLBACK_BOARD_KEYWORD_SQL, FALLBACK_BOARD_KEYWORD_COUNT_SQL)
                : new SearchSqlSet(FALLBACK_GLOBAL_KEYWORD_SQL, FALLBACK_GLOBAL_KEYWORD_COUNT_SQL);
    }

    private SearchSqlSet selectSliceSqlSet(boolean boardSearch, boolean postgres) {
        if (postgres) {
            return boardSearch
                    ? new SearchSqlSet(POSTGRES_BOARD_KEYWORD_SLICE_SQL, "")
                    : new SearchSqlSet(POSTGRES_GLOBAL_KEYWORD_SLICE_SQL, "");
        }
        return boardSearch
                ? new SearchSqlSet(FALLBACK_BOARD_KEYWORD_SLICE_SQL, "")
                : new SearchSqlSet(FALLBACK_GLOBAL_KEYWORD_SLICE_SQL, "");
    }

    private boolean isPostgresDatabase() {
        Boolean cached = postgresDatabase;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (postgresDatabase == null) {
                postgresDatabase = detectDatabaseProduct()
                        .toLowerCase(Locale.ROOT)
                        .contains("postgres");
            }
            return postgresDatabase;
        }
    }

    private String detectDatabaseProduct() {
        return jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName()
        );
    }

    private PostRepository.PostListRow mapPostListRow(Object[] row) {
        return new NativePostListRow(
                toLong(row[0]),
                (String) row[1],
                (String) row[2],
                toLocalDateTime(row[3]),
                toLocalDateTime(row[4]),
                toInt(row[5]),
                toLong(row[6]),
                toInt(row[7]),
                toBoolean(row[8]),
                toBoolean(row[9]),
                (String) row[10],
                (String) row[11],
                (String) row[12],
                (String) row[13]
        );
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return ((Timestamp) value).toLocalDateTime();
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return false;
    }

    private record SearchSqlSet(String contentSql, String countSql) {
    }

    private static final class NativePostListRow implements PostRepository.PostListRow {
        private final Long id;
        private final String title;
        private final String content;
        private final LocalDateTime createdAt;
        private final LocalDateTime updatedAt;
        private final int commentCount;
        private final long likeCount;
        private final int viewCount;
        private final boolean pinned;
        private final boolean draft;
        private final String legacyImageUrl;
        private final String authorUsername;
        private final String boardName;
        private final String boardSlug;

        private NativePostListRow(Long id,
                                  String title,
                                  String content,
                                  LocalDateTime createdAt,
                                  LocalDateTime updatedAt,
                                  int commentCount,
                                  long likeCount,
                                  int viewCount,
                                  boolean pinned,
                                  boolean draft,
                                  String legacyImageUrl,
                                  String authorUsername,
                                  String boardName,
                                  String boardSlug) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.commentCount = commentCount;
            this.likeCount = likeCount;
            this.viewCount = viewCount;
            this.pinned = pinned;
            this.draft = draft;
            this.legacyImageUrl = legacyImageUrl;
            this.authorUsername = authorUsername;
            this.boardName = boardName;
            this.boardSlug = boardSlug;
        }

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public String getContent() {
            return content;
        }

        @Override
        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        @Override
        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        @Override
        public int getCommentCount() {
            return commentCount;
        }

        @Override
        public long getLikeCount() {
            return likeCount;
        }

        @Override
        public int getViewCount() {
            return viewCount;
        }

        @Override
        public boolean isPinned() {
            return pinned;
        }

        @Override
        public boolean isDraft() {
            return draft;
        }

        @Override
        public String getLegacyImageUrl() {
            return legacyImageUrl;
        }

        @Override
        public String getAuthorUsername() {
            return authorUsername;
        }

        @Override
        public String getBoardName() {
            return boardName;
        }

        @Override
        public String getBoardSlug() {
            return boardSlug;
        }
    }
}
