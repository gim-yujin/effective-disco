package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Post;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class PostResponse {
    private final Long id;
    private final String title;
    private final String content;
    private final String author;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final int commentCount;
    private final long likeCount;
    private final int viewCount;
    private final List<String> tags;

    public PostResponse(Post post, long likeCount) {
        this.id = post.getId();
        this.title = post.getTitle();
        this.content = post.getContent();
        this.author = post.getAuthor().getUsername();
        this.createdAt = post.getCreatedAt();
        this.updatedAt = post.getUpdatedAt();
        this.commentCount = post.getComments().size();
        this.likeCount = likeCount;
        this.viewCount = post.getViewCount();
        this.tags = post.getTags().stream()
                .map(t -> t.getName())
                .sorted()
                .collect(Collectors.toList());
    }

    public PostResponse(Post post) {
        this(post, 0);
    }
}
