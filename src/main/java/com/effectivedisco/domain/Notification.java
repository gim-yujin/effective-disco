package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 알림 엔티티.
 * 댓글·대댓글·좋아요 이벤트 발생 시 수신자에게 생성된다.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 알림을 받는 사용자 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    /** 알림 메시지 (예: "user1님이 댓글을 남겼습니다") */
    @Column(nullable = false)
    private String message;

    /** 클릭 시 이동할 URL (예: "/posts/42#comments") */
    @Column(nullable = false)
    private String link;

    /** 읽음 여부. 알림 목록 페이지 방문 시 일괄 true로 변경된다. */
    @Column(nullable = false)
    private boolean isRead = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public Notification(User recipient, NotificationType type, String message, String link) {
        this.recipient = recipient;
        this.type      = type;
        this.message   = message;
        this.link      = link;
        this.createdAt = LocalDateTime.now();
    }

    public void markAsRead() {
        this.isRead = true;
    }
}
