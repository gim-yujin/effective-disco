package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 간 1:1 쪽지(DM) 엔티티.
 *
 * 삭제 정책: 실제 DELETE 대신 deletedBySender / deletedByRecipient 플래그를 사용한다.
 * 양쪽이 모두 삭제하면 데이터를 보존하되 양측에서 보이지 않게 된다.
 * (별도 배치로 정리하거나 그대로 유지해도 무방.)
 */
@Entity
@Table(name = "messages")
@Getter
@NoArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 발신자 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    /** 수신자 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    /** 제목 */
    @Column(nullable = false)
    private String title;

    /** 본문 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 수신자 읽음 여부 */
    @Column(nullable = false)
    private boolean isRead = false;

    /** 발신자가 보낸 편지함에서 삭제 */
    @Column(nullable = false)
    private boolean deletedBySender = false;

    /** 수신자가 받은 편지함에서 삭제 */
    @Column(nullable = false)
    private boolean deletedByRecipient = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public Message(User sender, User recipient, String title, String content) {
        this.sender    = sender;
        this.recipient = recipient;
        this.title     = title;
        this.content   = content;
        this.createdAt = LocalDateTime.now();
    }

    public void markAsRead()            { this.isRead = true; }
    public void deleteFromSent()        { this.deletedBySender = true; }
    public void deleteFromInbox()       { this.deletedByRecipient = true; }
}
