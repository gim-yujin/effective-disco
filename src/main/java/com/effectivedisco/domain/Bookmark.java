package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * 게시물 북마크(스크랩) 엔티티.
 *
 * 삭제 정책:
 * - 게시물 삭제 시 FK ON DELETE CASCADE(@OnDelete)로 자동 삭제
 * - 사용자 탈퇴 시에도 동일하게 CASCADE 처리
 */
@Entity
@Table(name = "bookmarks",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_bookmark_user_post",
               columnNames = {"user_id", "post_id"}),
       indexes = @Index(name = "idx_bookmark_user", columnList = "user_id"))
@Getter
@NoArgsConstructor
public class Bookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Post post;

    /**
     * 북마크 폴더 (nullable).
     * null이면 미분류(기본) 북마크로 간주한다.
     * 폴더 삭제 시 해당 폴더의 북마크는 미분류로 복원된다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private BookmarkFolder folder;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Bookmark(User user, Post post) {
        this.user      = user;
        this.post      = post;
        this.createdAt = LocalDateTime.now();
    }

    public Bookmark(User user, Post post, BookmarkFolder folder) {
        this.user      = user;
        this.post      = post;
        this.folder    = folder;
        this.createdAt = LocalDateTime.now();
    }

    /** 북마크의 폴더를 변경한다 (null이면 미분류) */
    public void moveToFolder(BookmarkFolder folder) {
        this.folder = folder;
    }
}
