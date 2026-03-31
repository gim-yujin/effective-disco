package com.effectivedisco.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "댓글 작성/수정 요청")
public class CommentRequest {

    /** 댓글 본문 — 최대 3,000자로 제한하여 과도한 댓글을 방지한다. */
    @Schema(description = "댓글 내용 (최대 3,000자)", example = "좋은 글 감사합니다!")
    @NotBlank
    @Size(max = 3_000, message = "댓글은 3,000자 이내로 입력하세요.")
    private String content;
}
