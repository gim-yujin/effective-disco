-- 문제 해결:
-- mixed scenario가 늘어나면서 좋아요 외에도 bookmark/follow/block 중복 row를 함께 봐야
-- "카운터는 맞는데 관계 테이블이 깨진 상태"를 놓치지 않는다.
WITH scope_users AS (
    SELECT u.id
    FROM users u
    WHERE u.username LIKE :'loadtest_prefix' || '%'
),
scope_posts AS (
    SELECT p.id
    FROM posts p
    WHERE p.user_id IN (SELECT id FROM scope_users)
),
scope_notifications AS (
    SELECT n.id
    FROM notifications n
    WHERE n.recipient_id IN (SELECT id FROM scope_users)
),
duplicate_bookmarks AS (
    SELECT COUNT(*) AS value
    FROM (
        SELECT b.user_id, b.post_id
        FROM bookmarks b
        WHERE b.user_id IN (SELECT id FROM scope_users)
           OR b.post_id IN (SELECT id FROM scope_posts)
        GROUP BY b.user_id, b.post_id
        HAVING COUNT(*) > 1
    ) duplicates
),
duplicate_follows AS (
    SELECT COUNT(*) AS value
    FROM (
        SELECT f.follower_id, f.following_id
        FROM follows f
        WHERE f.follower_id IN (SELECT id FROM scope_users)
           OR f.following_id IN (SELECT id FROM scope_users)
        GROUP BY f.follower_id, f.following_id
        HAVING COUNT(*) > 1
    ) duplicates
),
duplicate_blocks AS (
    SELECT COUNT(*) AS value
    FROM (
        SELECT b.blocker_id, b.blocked_id
        FROM blocks b
        WHERE b.blocker_id IN (SELECT id FROM scope_users)
           OR b.blocked_id IN (SELECT id FROM scope_users)
        GROUP BY b.blocker_id, b.blocked_id
        HAVING COUNT(*) > 1
    ) duplicates
),
duplicate_post_likes AS (
    SELECT COUNT(*) AS value
    FROM (
        SELECT pl.post_id, pl.user_id
        FROM post_likes pl
        WHERE pl.user_id IN (SELECT id FROM scope_users)
           OR pl.post_id IN (SELECT id FROM scope_posts)
        GROUP BY pl.post_id, pl.user_id
        HAVING COUNT(*) > 1
    ) duplicates
),
post_like_mismatch_posts AS (
    SELECT COUNT(*) AS value
    FROM (
        SELECT p.id
        FROM posts p
        LEFT JOIN (
            SELECT pl.post_id, COUNT(*) AS actual_like_count
            FROM post_likes pl
            GROUP BY pl.post_id
        ) likes ON likes.post_id = p.id
        WHERE p.id IN (SELECT id FROM scope_posts)
          AND p.like_count <> COALESCE(likes.actual_like_count, 0)
    ) mismatches
),
negative_like_count_posts AS (
    SELECT COUNT(*) AS value
    FROM posts p
    WHERE p.id IN (SELECT id FROM scope_posts)
      AND p.like_count < 0
),
post_comment_mismatch_posts AS (
    SELECT COUNT(*) AS value
    FROM (
        SELECT p.id
        FROM posts p
        LEFT JOIN (
            SELECT c.post_id, COUNT(*) AS actual_comment_count
            FROM comments c
            GROUP BY c.post_id
        ) comments ON comments.post_id = p.id
        WHERE p.id IN (SELECT id FROM scope_posts)
          AND p.comment_count <> COALESCE(comments.actual_comment_count, 0)
    ) mismatches
),
negative_comment_count_posts AS (
    SELECT COUNT(*) AS value
    FROM posts p
    WHERE p.id IN (SELECT id FROM scope_posts)
      AND p.comment_count < 0
),
unread_notification_mismatch_users AS (
    SELECT COUNT(*) AS value
    FROM (
        SELECT u.id
        FROM users u
        LEFT JOIN (
            SELECT n.recipient_id, COUNT(*) AS actual_unread_count
            FROM notifications n
            WHERE n.is_read = false
            GROUP BY n.recipient_id
        ) unread ON unread.recipient_id = u.id
        WHERE u.id IN (SELECT id FROM scope_users)
          AND u.unread_notification_count <> COALESCE(unread.actual_unread_count, 0)
    ) mismatches
),
negative_unread_notification_users AS (
    SELECT COUNT(*) AS value
    FROM users u
    WHERE u.id IN (SELECT id FROM scope_users)
      AND u.unread_notification_count < 0
)
SELECT
    (SELECT COUNT(*) FROM scope_users) AS scope_user_count,
    (SELECT COUNT(*) FROM scope_posts) AS scope_post_count,
    (SELECT COUNT(*) FROM scope_notifications) AS scope_notification_count,
    (SELECT value FROM duplicate_post_likes) AS duplicate_post_likes,
    (SELECT value FROM duplicate_bookmarks) AS duplicate_bookmarks,
    (SELECT value FROM duplicate_follows) AS duplicate_follows,
    (SELECT value FROM duplicate_blocks) AS duplicate_blocks,
    (SELECT value FROM post_like_mismatch_posts) AS post_like_mismatch_posts,
    (SELECT value FROM negative_like_count_posts) AS negative_like_count_posts,
    (SELECT value FROM post_comment_mismatch_posts) AS post_comment_mismatch_posts,
    (SELECT value FROM negative_comment_count_posts) AS negative_comment_count_posts,
    (SELECT value FROM unread_notification_mismatch_users) AS unread_notification_mismatch_users,
    (SELECT value FROM negative_unread_notification_users) AS negative_unread_notification_users;
