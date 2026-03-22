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

@Entity
@Table(name = "posts")
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

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int viewCount = 0;

    /** 관리자가 게시판 상단에 고정한 공지 게시물 여부 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean pinned = false;

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

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "post_tags",
               joinColumns = @JoinColumn(name = "post_id"),
               inverseJoinColumns = @JoinColumn(name = "tag_id"))
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
