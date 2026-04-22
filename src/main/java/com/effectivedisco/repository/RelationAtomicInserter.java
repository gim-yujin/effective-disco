package com.effectivedisco.repository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * block/bookmark/follow 관계를 FOR UPDATE lock 없이 원자적으로 삽입한다.
 *
 * - PostgreSQL: {@code INSERT ... ON CONFLICT DO NOTHING} — race-safe upsert가 단일 문으로 보장됨.
 * - H2 (테스트): {@code MERGE INTO ... KEY(...) VALUES (...)} — H2 네이티브 upsert 구문.
 *
 * 두 방언이 달라 JPA @Query로는 단일 쿼리 선언이 불가능하므로 기동 시 1회
 * {@link Connection#getMetaData()} 로 방언을 감지해 적절한 SQL을 고정한다.
 */
@Component
@RequiredArgsConstructor
public class RelationAtomicInserter {

    private final JdbcTemplate jdbc;

    private String blockInsertSql;
    private String bookmarkInsertSql;
    private String followInsertSql;
    private String postLikeInsertSql;
    private String commentLikeInsertSql;
    private boolean h2Dialect;

    @PostConstruct
    void init() throws SQLException {
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            boolean h2 = product != null && product.toUpperCase().contains("H2");
            this.h2Dialect = h2;
            if (h2) {
                // H2 네이티브 MERGE 구문 (KEY 절이 UNIQUE 제약 컬럼을 지정).
                blockInsertSql    = "MERGE INTO blocks (blocker_id, blocked_id, created_at) "
                                  + "KEY (blocker_id, blocked_id) VALUES (?, ?, ?)";
                bookmarkInsertSql = "MERGE INTO bookmarks (user_id, post_id, folder_id, created_at) "
                                  + "KEY (user_id, post_id) VALUES (?, ?, NULL, ?)";
                followInsertSql   = "MERGE INTO follows (follower_id, following_id, created_at) "
                                  + "KEY (follower_id, following_id) VALUES (?, ?, ?)";
                // post.like 는 counter UPDATE 게이팅을 위해 "실제 신규 삽입 건수"를 정확히 반환해야 한다.
                // H2 MERGE 는 기존 행이 있어도 업데이트 카운트를 1로 보고하므로 사용할 수 없다.
                // 대신 INSERT ... SELECT ... WHERE NOT EXISTS 구문으로 pre-check + insert 를
                // 단일 문에 묶고, race 로 2개 스레드가 동시에 NOT EXISTS 를 통과할 경우에는
                // unique 제약이 나중 INSERT 를 거르고 {@link DuplicateKeyException} 이 발생하면
                // 호출자가 0 으로 해석하도록 한다.
                postLikeInsertSql = "INSERT INTO post_likes (post_id, user_id, created_at) "
                                  + "SELECT ?, ?, ? WHERE NOT EXISTS ("
                                  + "SELECT 1 FROM post_likes WHERE post_id = ? AND user_id = ?)";
                // comment.like 도 post.like 와 동일한 이유로 MERGE 대신 INSERT ... WHERE NOT EXISTS 로 실제 삽입 건수를 반환해
                // counter UPDATE / 알림 이벤트 게이팅에 사용한다.
                commentLikeInsertSql = "INSERT INTO comment_likes (comment_id, user_id, created_at) "
                                     + "SELECT ?, ?, ? WHERE NOT EXISTS ("
                                     + "SELECT 1 FROM comment_likes WHERE comment_id = ? AND user_id = ?)";
            } else {
                // PostgreSQL: ON CONFLICT DO NOTHING — race-free idempotent 삽입.
                blockInsertSql    = "INSERT INTO blocks (blocker_id, blocked_id, created_at) "
                                  + "VALUES (?, ?, ?) ON CONFLICT (blocker_id, blocked_id) DO NOTHING";
                bookmarkInsertSql = "INSERT INTO bookmarks (user_id, post_id, folder_id, created_at) "
                                  + "VALUES (?, ?, NULL, ?) ON CONFLICT (user_id, post_id) DO NOTHING";
                followInsertSql   = "INSERT INTO follows (follower_id, following_id, created_at) "
                                  + "VALUES (?, ?, ?) ON CONFLICT (follower_id, following_id) DO NOTHING";
                postLikeInsertSql = "INSERT INTO post_likes (post_id, user_id, created_at) "
                                  + "VALUES (?, ?, ?) ON CONFLICT (post_id, user_id) DO NOTHING";
                commentLikeInsertSql = "INSERT INTO comment_likes (comment_id, user_id, created_at) "
                                     + "VALUES (?, ?, ?) ON CONFLICT (comment_id, user_id) DO NOTHING";
            }
        }
    }

    public int insertBlock(Long blockerId, Long blockedId, LocalDateTime createdAt) {
        return jdbc.update(blockInsertSql, blockerId, blockedId, createdAt);
    }

    public int insertBookmark(Long userId, Long postId, LocalDateTime createdAt) {
        return jdbc.update(bookmarkInsertSql, userId, postId, createdAt);
    }

    public int insertFollow(Long followerId, Long followingId, LocalDateTime createdAt) {
        return jdbc.update(followInsertSql, followerId, followingId, createdAt);
    }

    public int insertPostLike(Long postId, Long userId, LocalDateTime createdAt) {
        if (h2Dialect) {
            // H2 경로: INSERT ... WHERE NOT EXISTS (postId, userId) + race 시 unique 위반 캐치
            try {
                return jdbc.update(postLikeInsertSql, postId, userId, createdAt, postId, userId);
            } catch (DuplicateKeyException ignored) {
                return 0;
            }
        }
        return jdbc.update(postLikeInsertSql, postId, userId, createdAt);
    }

    public int insertCommentLike(Long commentId, Long userId, LocalDateTime createdAt) {
        if (h2Dialect) {
            try {
                return jdbc.update(commentLikeInsertSql, commentId, userId, createdAt, commentId, userId);
            } catch (DuplicateKeyException ignored) {
                return 0;
            }
        }
        return jdbc.update(commentLikeInsertSql, commentId, userId, createdAt);
    }
}
