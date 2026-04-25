package com.effectivedisco.loadtest;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문제 해결:
 * 현재 load test는 실제 회원/게시물/댓글을 생성하므로 같은 개발 DB를 공유하면
 * k6를 돌릴수록 데이터가 누적돼 다음 측정과 수동 확인이 모두 오염된다.
 * 실행 prefix(username 시작값) 기준으로 해당 런이 만든 사용자 범위를 회수해
 * 측정 직후 DB를 다시 깨끗한 상태로 되돌린다.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")
public class LoadTestDataCleanupService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public CleanupSummary cleanupByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("load test cleanup prefix must not be blank");
        }

        String prefixPattern = prefix + "%";
        prepareScopeTables(prefixPattern);

        long matchedUsers = count("SELECT COUNT(*) FROM loadtest_cleanup_users");
        long matchedPosts = count("SELECT COUNT(*) FROM loadtest_cleanup_posts");
        long matchedComments = count("""
                SELECT COUNT(*)
                FROM comments c
                WHERE c.user_id IN (SELECT id FROM loadtest_cleanup_users)
                """);
        long matchedNotifications = count("""
                SELECT COUNT(*)
                FROM notifications n
                WHERE n.recipient_id IN (SELECT id FROM loadtest_cleanup_users)
                """);
        long matchedMessages = count("""
                SELECT COUNT(*)
                FROM messages m
                WHERE m.sender_id IN (SELECT id FROM loadtest_cleanup_users)
                   OR m.recipient_id IN (SELECT id FROM loadtest_cleanup_users)
                """);
        long matchedPasswordResetTokens = count("""
                SELECT COUNT(*)
                FROM password_reset_tokens t
                WHERE t.user_id IN (SELECT id FROM loadtest_cleanup_users)
                """);

        deleteScope();
        clearScopeTables();

        return new CleanupSummary(
                prefix,
                matchedUsers,
                matchedPosts,
                matchedComments,
                matchedNotifications,
                matchedMessages,
                matchedPasswordResetTokens
        );
    }

    private void prepareScopeTables(String prefixPattern) {
        jdbcTemplate.execute("CREATE TEMPORARY TABLE IF NOT EXISTS loadtest_cleanup_users (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TEMPORARY TABLE IF NOT EXISTS loadtest_cleanup_posts (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("CREATE TEMPORARY TABLE IF NOT EXISTS loadtest_cleanup_comments (id BIGINT PRIMARY KEY)");
        clearScopeTables();

        jdbcTemplate.update("""
                INSERT INTO loadtest_cleanup_users (id)
                SELECT u.id
                FROM users u
                WHERE u.username LIKE ?
                """, prefixPattern);
        jdbcTemplate.update("""
                INSERT INTO loadtest_cleanup_posts (id)
                SELECT p.id
                FROM posts p
                WHERE p.user_id IN (SELECT id FROM loadtest_cleanup_users)
                """);
        jdbcTemplate.update("""
                INSERT INTO loadtest_cleanup_comments (id)
                SELECT id
                FROM (
                    WITH RECURSIVE comments_to_delete(id) AS (
                        SELECT c.id
                        FROM comments c
                        WHERE c.user_id IN (SELECT id FROM loadtest_cleanup_users)
                           OR c.post_id IN (SELECT id FROM loadtest_cleanup_posts)
                        UNION
                        SELECT child.id
                        FROM comments child
                        JOIN comments_to_delete parent ON child.parent_id = parent.id
                    )
                    SELECT id FROM comments_to_delete
                ) scoped_comments
                """);
    }

    private void clearScopeTables() {
        jdbcTemplate.update("DELETE FROM loadtest_cleanup_comments");
        jdbcTemplate.update("DELETE FROM loadtest_cleanup_posts");
        jdbcTemplate.update("DELETE FROM loadtest_cleanup_users");
    }

    private void deleteScope() {
        jdbcTemplate.update("DELETE FROM password_reset_tokens WHERE user_id IN (SELECT id FROM loadtest_cleanup_users)");
        jdbcTemplate.update("DELETE FROM notification_settings WHERE user_id IN (SELECT id FROM loadtest_cleanup_users)");
        jdbcTemplate.update("DELETE FROM notifications WHERE recipient_id IN (SELECT id FROM loadtest_cleanup_users)");
        jdbcTemplate.update("""
                DELETE FROM messages
                WHERE sender_id IN (SELECT id FROM loadtest_cleanup_users)
                   OR recipient_id IN (SELECT id FROM loadtest_cleanup_users)
                """);

        jdbcTemplate.update("""
                DELETE FROM comment_likes
                WHERE comment_id IN (SELECT id FROM loadtest_cleanup_comments)
                   OR user_id IN (SELECT id FROM loadtest_cleanup_users)
                """);
        jdbcTemplate.update("""
                DELETE FROM post_likes
                WHERE post_id IN (SELECT id FROM loadtest_cleanup_posts)
                   OR user_id IN (SELECT id FROM loadtest_cleanup_users)
                """);
        jdbcTemplate.update("""
                DELETE FROM bookmarks
                WHERE post_id IN (SELECT id FROM loadtest_cleanup_posts)
                   OR user_id IN (SELECT id FROM loadtest_cleanup_users)
                """);
        jdbcTemplate.update("""
                DELETE FROM follows
                WHERE follower_id IN (SELECT id FROM loadtest_cleanup_users)
                   OR following_id IN (SELECT id FROM loadtest_cleanup_users)
                """);
        jdbcTemplate.update("""
                DELETE FROM blocks
                WHERE blocker_id IN (SELECT id FROM loadtest_cleanup_users)
                   OR blocked_id IN (SELECT id FROM loadtest_cleanup_users)
                """);
        jdbcTemplate.update("""
                DELETE FROM reports
                WHERE reporter_id IN (SELECT id FROM loadtest_cleanup_users)
                   OR (target_type = 'POST' AND target_id IN (SELECT id FROM loadtest_cleanup_posts))
                   OR (target_type = 'COMMENT' AND target_id IN (SELECT id FROM loadtest_cleanup_comments))
                """);

        jdbcTemplate.update("DELETE FROM post_tags WHERE post_id IN (SELECT id FROM loadtest_cleanup_posts)");
        jdbcTemplate.update("DELETE FROM post_images WHERE post_id IN (SELECT id FROM loadtest_cleanup_posts)");
        jdbcTemplate.update("""
                DELETE FROM comments
                WHERE id IN (SELECT id FROM loadtest_cleanup_comments)
                """);
        jdbcTemplate.update("DELETE FROM posts WHERE id IN (SELECT id FROM loadtest_cleanup_posts)");
        jdbcTemplate.update("DELETE FROM bookmark_folders WHERE user_id IN (SELECT id FROM loadtest_cleanup_users)");
        jdbcTemplate.update("DELETE FROM users WHERE id IN (SELECT id FROM loadtest_cleanup_users)");
    }

    private long count(String sql, Object... args) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, args);
        return result == null ? 0L : result;
    }

    public record CleanupSummary(
            String prefix,
            long matchedUsers,
            long matchedPosts,
            long matchedComments,
            long matchedNotifications,
            long matchedMessages,
            long matchedPasswordResetTokens
    ) {}
}
