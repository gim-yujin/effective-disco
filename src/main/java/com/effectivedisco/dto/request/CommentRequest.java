package com.effectivedisco.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "댓글 작성/수정 요청")
public class CommentRequest {

    @Schema(description = "댓글 내용", example = "좋은 글 감사합니다!")
    @NotBlank
    private String content;
}
