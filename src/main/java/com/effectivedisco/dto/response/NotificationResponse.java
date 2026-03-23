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

    @Schema(description = "읽음 여부 (알림 목록 페이지 방문 시 true 로 일괄 변경됨)", example = "false")
    private final boolean read;

    @Schema(description = "알림 생성 시각")
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
