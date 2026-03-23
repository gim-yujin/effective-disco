package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Comment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "댓글 응답 DTO")
@Getter
public class CommentResponse {

    @Schema(description = "댓글 고유 ID", example = "7")
    private final Long id;

    @Schema(description = "댓글 내용", example = "좋은 글 감사합니다!")
    private final String content;

    @Schema(description = "작성자 username", example = "alice")
    private final String author;

    @Schema(description = "작성 시각")
    private final LocalDateTime createdAt;

    @Schema(description = "최종 수정 시각 (수정된 경우 '(수정됨)' 표시용)")
    private final LocalDateTime updatedAt;

    @Schema(description = "대댓글 목록 (최대 1단계 중첩)")
    private final List<CommentResponse> replies;

    /**
     * 작성자 프로필 이미지 URL.
     * null 이면 템플릿에서 이니셜 아바타를 렌더링한다.
     */
    @Schema(description = "작성자 프로필 이미지 URL (null 이면 이니셜 아바타 표시)",
            example = "/uploads/profile/alice.jpg", nullable = true)
    private final String authorProfileImageUrl;

    public CommentResponse(Comment comment) {
        this.id                   = comment.getId();
        this.content              = comment.getContent();
        this.author               = comment.getAuthor().getUsername();
        this.createdAt            = comment.getCreatedAt();
        this.updatedAt            = comment.getUpdatedAt();
        this.authorProfileImageUrl = comment.getAuthor().getProfileImageUrl();
        // 대댓글도 재귀적으로 같은 DTO로 변환
        this.replies = comment.getReplies().stream()
                .map(CommentResponse::new)
                .toList();
    }
}
