package com.effectivedisco.dto.response;

import java.time.LocalDateTime;

public record PostScrollCursor(LocalDateTime createdAt, Long id) {
}
