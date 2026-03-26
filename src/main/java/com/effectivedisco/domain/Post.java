package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 문제 해결:
 * 게시판 목록/검색과 ranked browse 는 모두 `board_id + draft` 범위를 먼저 줄인 뒤
 * `created_at`, `like_count`, `comment_count` 정렬을 반복한다.
 * latest/likes/comments 각각이 다른 sort key 를 쓰므로,
 * board 범위 내 keyset window 를 바로 잘라낼 수 있게 정렬별 복합 인덱스를 둔다.
 */
@Entity
@Table(name = "posts", indexes = {
        @Index(name = "idx_post_board_draft", columnList = "board_id, draft"),
        @Index(name = "idx_post_board_draft_created_at_desc", columnList = "board_id, draft, created_at DESC"),
        @Index(name = "idx_post_board_draft_like_created_id_desc", columnList = "board_id, draft, like_count DESC, created_at DESC, id DESC"),
        @Index(name = "idx_post_board_draft_comment_created_id_desc", columnList = "board_id, draft, comment_count DESC, created_at DESC, id DESC"),
        @Index(name = "idx_post_user", columnList = "user_id")
})
@Getter
@NoArgsConstructor
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User author;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    @Version
    private Long version;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int viewCount = 0;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int likeCount = 0;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int commentCount = 0;

    /** 관리자가 게시판 상단에 고정한 공지 게시물 여부 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean pinned = false;

    /**
     * 초안(비공개 임시저장) 여부.
     * true이면 작성자 본인만 볼 수 있고 공개 게시물 목록에서 제외된다.
     * false(기본값)이면 즉시 공개된다.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean draft = false;

    /** 첨부 이미지 URL (없으면 null) */
    @Column
    private String imageUrl;

    /**
     * 이 게시물이 속한 게시판.
     * nullable = true: 게시판 기능 도입 이전에 작성된 기존 게시물과의 하위 호환을 위해
     * NULL을 허용한다. NULL인 게시물은 "미분류"로 처리한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id")
    private Board board;

    /**
     * 문제 해결:
     * 태그 기반 목록/카운트는 `tag -> post` 방향 탐색이 핵심인데,
     * 기존 PK `(post_id, tag_id)` 만으로는 `tag_id` 선행 탐색이 비효율적이었다.
     * `tag_id, post_id` 인덱스를 추가해 post_tags full scan 성격을 줄인다.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "post_tags",
               joinColumns = @JoinColumn(name = "post_id"),
               inverseJoinColumns = @JoinColumn(name = "tag_id"),
               indexes = {
                   @Index(name = "idx_post_tags_tag_post", columnList = "tag_id, post_id")
               })
    private Set<Tag> tags = new HashSet<>();

    /**
     * 첨부 이미지 목록. sortOrder 오름차순으로 정렬된다.
     * CascadeType.ALL + orphanRemoval=true: 이미지를 컬렉션에서 제거하면 DB에서도 자동 삭제.
     * DB 레벨 CASCADE(@OnDelete)와 JPA 레벨 cascade 를 모두 설정해 어느 쪽에서 삭제하든 일관성을 보장한다.
     */
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<PostImage> images = new ArrayList<>();

    /**
     * board 파라미터를 포함한 빌더 생성자.
     * board를 지정하지 않으면 null(미분류)로 처리된다.
     */
    @Builder
    public Post(String title, String content, User author, Board board) {
        this.title     = title;
        this.content   = content;
        this.author    = author;
        this.board     = board;
        this.createdAt = LocalDateTime.now();
    }

    public void update(String title, String content) {
        this.title = title;
        this.content = content;
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementViewCount() { this.viewCount++; }

    public void pin()   { this.pinned = true;  }
    public void unpin() { this.pinned = false; }

    /** 초안으로 전환한다 — 공개 목록에서 숨겨진다. */
    public void saveDraft() { this.draft = true;  }

    /** 초안을 발행(공개)한다 — 공개 목록에 노출된다. */
    public void publish()   { this.draft = false; }

    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    /**
     * 기존 이미지 목록을 모두 제거한다.
     * 수정 시 기존 이미지를 전부 삭제하고 새 이미지로 교체할 때 사용한다.
     * orphanRemoval = true 이므로 컬렉션에서 제거하면 DB 행도 삭제된다.
     */
    public void clearImages() { this.images.clear(); }

    /**
     * 이미지를 목록에 추가한다.
     * 양방향 연관관계의 owner(PostImage.post)와 일관성을 유지하기 위해 이 메서드를 통해 추가한다.
     */
    public void addImage(PostImage image) { this.images.add(image); }
}
