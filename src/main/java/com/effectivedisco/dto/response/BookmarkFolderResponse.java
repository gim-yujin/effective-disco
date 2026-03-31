package com.effectivedisco.dto.response;

import com.effectivedisco.domain.BookmarkFolder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 북마크 폴더 응답 DTO.
 */
@Schema(description = "북마크 폴더 응답")
@Getter
public class BookmarkFolderResponse {

    @Schema(description = "폴더 ID", example = "1")
    private final Long id;

    @Schema(description = "폴더 이름", example = "나중에 읽기")
    private final String name;

    @Schema(description = "생성 시각")
    private final LocalDateTime createdAt;

    public BookmarkFolderResponse(BookmarkFolder folder) {
        this.id        = folder.getId();
        this.name      = folder.getName();
        this.createdAt = folder.getCreatedAt();
    }
}
