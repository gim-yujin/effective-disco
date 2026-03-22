package com.effectivedisco.repository;

import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 최신순으로 수신자의 알림 목록 조회 */
    List<Notification> findByRecipientOrderByCreatedAtDesc(User recipient);

    /** 읽지 않은 알림 수 (헤더 뱃지용) */
    long countByRecipientAndIsReadFalse(User recipient);

    /** 수신자의 모든 미읽음 알림을 일괄 읽음 처리 */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.recipient = :recipient AND n.isRead = false")
    void markAllAsRead(@Param("recipient") User recipient);
}
