package com.effectivedisco.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "게시판 최신 목록 무한 스크롤 응답 DTO")
@Getter
public class PostScrollResponse {

    @Schema(description = "현재 배치의 게시물 목록")
    private final List<PostResponse> content;

    @Schema(description = "다음 커서 배치 존재 여부", example = "true")
    private final boolean hasNext;

    @Schema(description = "다음 요청에 전달할 createdAt 커서", nullable = true)
    private final LocalDateTime nextCursorCreatedAt;

    @Schema(description = "다음 요청에 전달할 ID 커서", nullable = true, example = "42")
    private final Long nextCursorId;

    public PostScrollResponse(List<PostResponse> content,
                              boolean hasNext,
                              LocalDateTime nextCursorCreatedAt,
                              Long nextCursorId) {
        this.content = List.copyOf(content);
        this.hasNext = hasNext;
        this.nextCursorCreatedAt = nextCursorCreatedAt;
        this.nextCursorId = nextCursorId;
    }
}
