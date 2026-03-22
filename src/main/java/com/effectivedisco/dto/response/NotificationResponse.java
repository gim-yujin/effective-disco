package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class NotificationResponse {

    private final Long id;
    private final NotificationType type;
    private final String message;
    private final String link;
    private final boolean read;
    private final LocalDateTime createdAt;

    public NotificationResponse(Notification n) {
        this.id        = n.getId();
        this.type      = n.getType();
        this.message   = n.getMessage();
        this.link      = n.getLink();
        this.read      = n.isRead();
        this.createdAt = n.getCreatedAt();
    }
}
