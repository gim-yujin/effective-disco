package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Comment;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class CommentResponse {
    private final Long id;
    private final String content;
    private final String author;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final List<CommentResponse> replies;

    /**
     * 작성자 프로필 이미지 URL.
     * null 이면 템플릿에서 이니셜 아바타를 렌더링한다.
     */
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
