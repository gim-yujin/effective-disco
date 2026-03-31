package com.effectivedisco.repository;

import com.effectivedisco.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    interface NotificationRecipientSnapshot {
        Long getId();
        String getUsername();
        long getUnreadNotificationCount();
    }

    interface CommentAuthorSnapshot {
        Long getId();
        String getUsername();
        String getProfileImageUrl();
    }

    interface PostCreateAuthorSnapshot {
        Long getId();
        String getUsername();
    }

    interface SecurityUserSnapshot {
        String getUsername();
        String getPassword();
        String getRole();
        boolean getSuspended();
        java.time.LocalDateTime getSuspendedUntil();
        String getSuspensionReason();
    }

    Optional<User> findByUsername(String username);

    /**
     * 문제 해결:
     * 같은 사용자가 같은 의도의 쓰기 요청을 동시에 여러 번 보내면
     * "조회 후 삽입" 사이에 경합이 생긴다. 요청 주체 User 행을 잠궈 해당 사용자의
     * 쓰기 요청을 직렬화하면 중복 insert race를 서비스 레이어에서 안전하게 막을 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.username = :username")
    Optional<User> findByUsernameForUpdate(@Param("username") String username);

    /**
     * 문제 해결:
     * notification store/read-page/read-all 은 같은 수신자 기준으로 직렬화돼야
     * unread counter refresh 와 notification row mutation 이 서로 엇갈려 drift 를 만들지 않는다.
     * 수신자 id 기준 잠금 경로를 두어 알림 mutation 들이 같은 사용자 행에서 순서대로 끝나게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    /**
     * 문제 해결:
     * comment.create 는 댓글 작성자 전체 엔티티를 읽지 않아도 user id / username / profile 만 있으면 충분하다.
     * 작성 응답과 연관 getReference 에 필요한 최소 컬럼만 projection 으로 읽어 추가 SELECT 를 줄인다.
     */
    @Query("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.profileImageUrl AS profileImageUrl
            FROM User u
            WHERE u.username = :username
            """)
    Optional<CommentAuthorSnapshot> findCommentAuthorSnapshotByUsername(@Param("username") String username);

    /**
     * 문제 해결:
     * createPost hot path 는 사용자 전체 엔티티를 읽지 않아도 user id / username 만 있으면 충분하다.
     * 게시물 작성 응답용 최소 컬럼만 projection 으로 읽어 write path SELECT fan-out 을 줄인다.
     */
    @Query("""
            SELECT u.id AS id,
                   u.username AS username
            FROM User u
            WHERE u.username = :username
            """)
    Optional<PostCreateAuthorSnapshot> findPostCreateAuthorSnapshotByUsername(@Param("username") String username);

    /**
     * 문제 해결:
     * JWT 인증 miss 와 로그인 인증은 전체 User 엔티티가 아니라 username/password/role/정지 정보만 있으면 된다.
     * 인증 hot path 를 최소 projection 으로 읽어 miss 시에도 pool 체류 시간을 줄인다.
     */
    @Query("""
            SELECT u.username AS username,
                   u.password AS password,
                   u.role AS role,
                   u.suspended AS suspended,
                   u.suspendedUntil AS suspendedUntil,
                   u.suspensionReason AS suspensionReason
            FROM User u
            WHERE u.username = :username
            """)
    Optional<SecurityUserSnapshot> findSecuritySnapshotByUsername(@Param("username") String username);

    @Query("SELECT u.unreadNotificationCount FROM User u WHERE u.username = :username")
    Optional<Long> findUnreadNotificationCountByUsername(@Param("username") String username);

    /**
     * 문제 해결:
     * notification.store/read-all hot path 는 사용자 전체 엔티티와 PESSIMISTIC_WRITE 잠금이 과했다.
     * 수신자 id/username/unread counter 만 projection 으로 읽으면 row hydration 비용을 줄이고
     * unread counter 갱신은 별도 원자 UPDATE 로 넘겨 lock 직렬화를 완화할 수 있다.
     */
    @Query("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.unreadNotificationCount AS unreadNotificationCount
            FROM User u
            WHERE u.username = :username
            """)
    Optional<NotificationRecipientSnapshot> findNotificationRecipientSnapshotByUsername(@Param("username") String username);

    /**
     * 문제 해결:
     * notification.store 는 수신자 직렬화는 필요하지만 User 전체 엔티티 hydration 은 불필요하다.
     * username 기준으로 unread counter 스냅샷만 FOR UPDATE 로 잠그면
     * store 경로의 row lock 은 유지하면서 entity materialize 비용과 후속 drift 보정 쿼리를 줄일 수 있다.
     */
    @Query(
            value = """
                    SELECT id AS id,
                           username AS username,
                           unread_notification_count AS unreadNotificationCount
                    FROM users
                    WHERE username = :username
                    FOR UPDATE
                    """,
            nativeQuery = true
    )
    Optional<NotificationRecipientSnapshot> findNotificationRecipientSnapshotByUsernameForUpdate(@Param("username") String username);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE User u
            SET u.unreadNotificationCount = u.unreadNotificationCount + 1
            WHERE u.id = :userId
            """)
    int incrementUnreadNotificationCount(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = """
                    UPDATE users
                    SET unread_notification_count = GREATEST(unread_notification_count - :delta, 0)
                    WHERE id = :userId
                    """,
            nativeQuery = true
    )
    int decrementUnreadNotificationCount(@Param("userId") Long userId, @Param("delta") long delta);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE User u
            SET u.unreadNotificationCount = :count
            WHERE u.id = :userId
            """)
    int setUnreadNotificationCount(@Param("userId") Long userId, @Param("count") long count);

    /**
     * 문제 해결:
     * read-page 는 visible unread id 일부만 읽음 처리하므로 서비스의 delta 계산으로 counter 를 맞추기보다
     * DB의 실제 unread row 수를 그대로 다시 써 넣는 편이 경합과 drift 에 더 강하다.
     * 단일 UPDATE 안에서 subquery count 를 반영해 store/read-page 경쟁 후에도 counter 를 실제값으로 수렴시킨다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = """
                    UPDATE users
                    SET unread_notification_count = (
                        SELECT COUNT(*)
                        FROM notifications
                        WHERE recipient_id = :userId
                          AND is_read = false
                    )
                    WHERE id = :userId
                    """,
            nativeQuery = true
    )
    int refreshUnreadNotificationCount(@Param("userId") Long userId);

    /** 접두사로 사용자명을 검색한다 (자동완성용, 최대 10건) */
    @Query("""
            SELECT u.username
            FROM User u
            WHERE LOWER(u.username) LIKE LOWER(CONCAT(:prefix, '%'))
            ORDER BY u.username ASC
            """)
    List<String> findUsernamesByPrefix(@Param("prefix") String prefix,
                                       org.springframework.data.domain.Pageable pageable);

    /** 관리자 패널: 가입일 최신순 전체 사용자 목록 */
    List<User> findAllByOrderByCreatedAtDesc();

    /**
     * 문제 해결:
     * load test가 만든 데이터는 실행 prefix를 username 앞에 붙여 구분한다.
     * cleanup 단계에서 같은 prefix 범위 사용자만 다시 찾아 안전하게 회수한다.
     */
    List<User> findByUsernameStartingWithOrderByIdAsc(String prefix);

    /** 비밀번호 재설정: 이메일로 사용자 조회 */
    Optional<User> findByEmail(String email);

    /** 관리자 초기화: ROLE_ADMIN 계정이 이미 있는지 확인 */
    boolean existsByRole(String role);
}
