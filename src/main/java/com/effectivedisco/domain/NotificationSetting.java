package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 알림 수신 설정 엔티티.
 *
 * 각 {@link NotificationType}에 대해 수신 on/off를 개별 관리한다.
 * 행이 없으면 해당 타입은 기본적으로 수신(enabled=true)으로 간주한다.
 */
@Entity
@Table(name = "notification_settings",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_notification_setting_user_type",
               columnNames = {"user_id", "notification_type"}))
@Getter
@NoArgsConstructor
public class NotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 알림 종류 (COMMENT, REPLY, LIKE, MESSAGE) */
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false)
    private NotificationType notificationType;

    /** true = 수신, false = 수신 거부 */
    @Column(nullable = false)
    private boolean enabled;

    public NotificationSetting(User user, NotificationType notificationType, boolean enabled) {
        this.user             = user;
        this.notificationType = notificationType;
        this.enabled          = enabled;
    }

    /** 수신 설정을 변경한다 */
    public void updateEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
