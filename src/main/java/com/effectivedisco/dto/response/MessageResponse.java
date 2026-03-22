package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Message;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class MessageResponse {

    private final Long   id;
    private final String senderUsername;
    private final String recipientUsername;
    private final String title;
    private final String content;
    private final boolean read;
    private final LocalDateTime createdAt;

    public MessageResponse(Message m) {
        this.id                = m.getId();
        this.senderUsername    = m.getSender().getUsername();
        this.recipientUsername = m.getRecipient().getUsername();
        this.title             = m.getTitle();
        this.content           = m.getContent();
        this.read              = m.isRead();
        this.createdAt         = m.getCreatedAt();
    }
}
