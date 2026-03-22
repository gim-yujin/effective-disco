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
}
