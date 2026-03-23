package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostImage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Schema(description = "게시물 응답 DTO")
@Getter
public class PostResponse {

    @Schema(description = "게시물 고유 ID", example = "42")
    private final Long id;

    @Schema(description = "게시물 제목", example = "Spring Boot 4.x 에서 달라진 점")
    private final String title;

    @Schema(description = "게시물 본문 (HTML 가능)", example = "이번 버전에서는 ...")
    private final String content;

    @Schema(description = "작성자 username", example = "alice")
    private final String author;

    @Schema(description = "작성 시각")
    private final LocalDateTime createdAt;

    @Schema(description = "최종 수정 시각")
    private final LocalDateTime updatedAt;

    @Schema(description = "댓글 수", example = "5")
    private final int commentCount;

    @Schema(description = "좋아요 수", example = "12")
    private final long likeCount;

    /**
     * 조회수.
     * 웹 UI에서 동일 세션 중복 카운트를 방지하기 위해 HttpSession 의
     * Set&lt;Long&gt; viewedPosts 로 관리되며, DB의 Post.viewCount 값을 그대로 반환한다.
     */
    @Schema(description = "조회수", example = "128")
    private final int viewCount;

    @Schema(description = "태그 이름 목록 (알파벳순 정렬)", example = "[\"java\", \"spring\"]")
    private final List<String> tags;

    /**
     * 게시물이 속한 게시판 이름·슬러그.
     * 게시판이 지정되지 않은 기존 게시물은 null을 반환한다.
     */
    @Schema(description = "게시판 이름 (null 가능)", example = "자유게시판")
    private final String boardName;

    @Schema(description = "게시판 슬러그 (null 가능)", example = "free")
    private final String boardSlug;

    @Schema(description = "공지 고정 여부", example = "false")
    private final boolean pinned;

    /**
     * 초안 여부. true이면 작성자 본인만 볼 수 있는 임시저장 게시물.
     * 초안 목록 페이지와 접근 제어 판단에 사용한다.
     */
    @Schema(description = "초안 여부. true 이면 작성자 본인만 열람 가능", example = "false")
    private final boolean draft;

    /**
     * 첨부 이미지 URL 목록 (sortOrder 오름차순).
     * PostImage 엔티티 목록을 우선 사용하고,
     * 기존 단일 imageUrl 필드(하위 호환)는 images가 비어 있을 때 폴백으로 포함한다.
     */
    @Schema(description = "첨부 이미지 URL 목록 (sortOrder 오름차순)",
            example = "[\"/uploads/img1.jpg\", \"/uploads/img2.png\"]")
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
