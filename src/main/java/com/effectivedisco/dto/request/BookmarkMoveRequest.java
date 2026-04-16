package com.effectivedisco.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "북마크 폴더 이동 요청")
public class BookmarkMoveRequest {

    /** 이동할 폴더 ID. null이면 미분류로 이동한다. */
    @Schema(description = "이동할 폴더 ID (null이면 미분류)", example = "1", nullable = true)
    private Long folderId;
}
