package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostImage;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    /**
     * 게시물이 속한 게시판 이름·슬러그.
     * 게시판이 지정되지 않은 기존 게시물은 null을 반환한다.
     */
    private final String boardName;
    private final String boardSlug;
    private final boolean pinned;

    /**
     * 초안 여부. true이면 작성자 본인만 볼 수 있는 임시저장 게시물.
     * 초안 목록 페이지와 접근 제어 판단에 사용한다.
     */
    private final boolean draft;

    /**
     * 첨부 이미지 URL 목록 (sortOrder 오름차순).
     * PostImage 엔티티 목록을 우선 사용하고,
     * 기존 단일 imageUrl 필드(하위 호환)는 images가 비어 있을 때 폴백으로 포함한다.
     */
    private final List<String> imageUrls;

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
        this.boardName = post.getBoard() != null ? post.getBoard().getName() : null;
        this.boardSlug = post.getBoard() != null ? post.getBoard().getSlug() : null;
        this.pinned    = post.isPinned();
        this.draft     = post.isDraft();

        // PostImage 컬렉션에서 URL 목록 구성 (sortOrder 기준 @OrderBy 적용됨)
        List<String> urls = post.getImages().stream()
                .map(PostImage::getImageUrl)
                .collect(Collectors.toCollection(ArrayList::new));
        // 기존 단일 imageUrl 필드 — PostImage가 없는 레거시 데이터 하위 호환
        if (urls.isEmpty() && post.getImageUrl() != null) {
            urls.add(post.getImageUrl());
        }
        this.imageUrls = List.copyOf(urls);
    }

    public PostResponse(Post post) {
        this(post, 0);
    }
}
