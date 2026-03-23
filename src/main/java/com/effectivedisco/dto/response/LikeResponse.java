package com.effectivedisco.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Schema(description = "좋아요 토글 응답 DTO")
@Getter
@AllArgsConstructor
public class LikeResponse {

    @Schema(description = "현재 로그인 사용자의 좋아요 여부 (true=좋아요 상태)", example = "true")
    private boolean liked;

    @Schema(description = "게시물 총 좋아요 수", example = "12")
    private long likeCount;
}
