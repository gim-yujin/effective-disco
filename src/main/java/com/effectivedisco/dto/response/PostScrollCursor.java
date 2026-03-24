package com.effectivedisco.dto.response;

import java.time.LocalDateTime;

public record PostScrollCursor(LocalDateTime createdAt, Long sortValue, Long id) {

    public PostScrollCursor(LocalDateTime createdAt, Long id) {
        this(createdAt, null, id);
    }
}
