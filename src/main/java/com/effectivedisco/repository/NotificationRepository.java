package com.effectivedisco.repository;

import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.NotificationResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 최신순으로 수신자의 알림 목록 조회 */
    List<Notification> findByRecipientOrderByCreatedAtDesc(User recipient);

    /**
     * 문제 해결:
     * 알림 페이지는 Notification 엔티티 전체보다 화면에 필요한 필드만 있으면 된다.
     * projection DTO로 바로 조회해 read-all 경로의 엔티티 materialize 비용을 줄인다.
     */
    @Query("""
            SELECT new com.effectivedisco.dto.response.NotificationResponse(
                n.id,
                n.type,
                n.message,
                n.link,
                n.isRead,
                n.createdAt
            )
            FROM Notification n
            WHERE n.recipient = :recipient
            ORDER BY n.createdAt DESC
            """)
    List<NotificationResponse> findResponseByRecipientOrderByCreatedAtDesc(@Param("recipient") User recipient);

    /** 읽지 않은 알림 수 (헤더 뱃지용) */
    long countByRecipientAndIsReadFalse(User recipient);

    boolean existsByRecipientAndIsReadFalse(User recipient);

    long countByRecipient(User recipient);

    /** 수신자의 모든 미읽음 알림을 일괄 읽음 처리 */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.recipient = :recipient AND n.isRead = false")
    void markAllAsRead(@Param("recipient") User recipient);

    /** 회원 탈퇴: 수신자의 모든 알림 삭제 */
    void deleteAllByRecipient(User recipient);
}
