package com.effectivedisco.repository;

import com.effectivedisco.domain.Message;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * 받은 편지함: 수신자가 삭제하지 않은 메시지를 최신순으로 반환.
     */
    List<Message> findByRecipientAndDeletedByRecipientFalseOrderByCreatedAtDesc(User recipient);

    /**
     * 보낸 편지함: 발신자가 삭제하지 않은 메시지를 최신순으로 반환.
     */
    List<Message> findBySenderAndDeletedBySenderFalseOrderByCreatedAtDesc(User sender);

    /**
     * 헤더 뱃지용: 읽지 않은 수신 메시지 수.
     */
    long countByRecipientAndIsReadFalseAndDeletedByRecipientFalse(User recipient);

    /** 회원 탈퇴: 발신자 또는 수신자인 메시지 전체 삭제 */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.sender = :user OR m.recipient = :user")
    void deleteAllByUser(@Param("user") User user);

    long countBySenderUsernameStartingWithOrRecipientUsernameStartingWith(String senderPrefix, String recipientPrefix);
}
