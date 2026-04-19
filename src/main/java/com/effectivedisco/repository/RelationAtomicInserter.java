package com.effectivedisco.repository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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

    @PostConstruct
    void init() throws SQLException {
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            boolean h2 = product != null && product.toUpperCase().contains("H2");
            if (h2) {
                // H2 네이티브 MERGE 구문 (KEY 절이 UNIQUE 제약 컬럼을 지정).
                blockInsertSql    = "MERGE INTO blocks (blocker_id, blocked_id, created_at) "
                                  + "KEY (blocker_id, blocked_id) VALUES (?, ?, ?)";
                bookmarkInsertSql = "MERGE INTO bookmarks (user_id, post_id, folder_id, created_at) "
                                  + "KEY (user_id, post_id) VALUES (?, ?, NULL, ?)";
                followInsertSql   = "MERGE INTO follows (follower_id, following_id, created_at) "
                                  + "KEY (follower_id, following_id) VALUES (?, ?, ?)";
            } else {
                // PostgreSQL: ON CONFLICT DO NOTHING — race-free idempotent 삽입.
                blockInsertSql    = "INSERT INTO blocks (blocker_id, blocked_id, created_at) "
                                  + "VALUES (?, ?, ?) ON CONFLICT (blocker_id, blocked_id) DO NOTHING";
                bookmarkInsertSql = "INSERT INTO bookmarks (user_id, post_id, folder_id, created_at) "
                                  + "VALUES (?, ?, NULL, ?) ON CONFLICT (user_id, post_id) DO NOTHING";
                followInsertSql   = "INSERT INTO follows (follower_id, following_id, created_at) "
                                  + "VALUES (?, ?, ?) ON CONFLICT (follower_id, following_id) DO NOTHING";
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
}
