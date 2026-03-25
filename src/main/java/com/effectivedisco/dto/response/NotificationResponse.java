package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;

@Schema(description = "알림 응답 DTO")
@Getter
public class NotificationResponse {

    @Schema(description = "알림 고유 ID", example = "3")
    private final Long id;

    @Schema(description = "알림 종류 (COMMENT·REPLY·LIKE·MESSAGE)", example = "COMMENT")
    private final NotificationType type;

    @Schema(description = "알림 메시지 본문", example = "alice님이 회원님의 게시물에 댓글을 남겼습니다.")
    private final String message;

    @Schema(description = "클릭 시 이동할 URL", example = "/posts/42#comments")
    private final String link;

    @Schema(description = "읽음 여부", example = "false")
    private final boolean read;

    @Schema(description = "알림 생성 시각")
    private final LocalDateTime createdAt;

    public NotificationResponse(Long id,
                                NotificationType type,
                                String message,
                                String link,
                                boolean read,
                                LocalDateTime createdAt) {
        this.id = id;
        this.type = type;
        this.message = message;
        this.link = link;
        this.read = read;
        this.createdAt = createdAt;
    }

    public NotificationResponse(Notification n) {
        this(
                n.getId(),
                n.getType(),
                n.getMessage(),
                n.getLink(),
                n.isRead(),
                n.getCreatedAt()
        );
    }

    /**
     * 문제 해결:
     * 명시적 read-all 이후 현재 view 에 읽음 상태를 즉시 반영해야 할 때
     * 엔티티를 다시 materialize 하지 않고 DTO view 만 읽음 상태로 덮어쓴다.
     */
    public NotificationResponse asRead() {
        if (read) {
            return this;
        }
        return new NotificationResponse(id, type, message, link, true, createdAt);
    }
}
