package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 북마크 폴더 엔티티.
 *
 * 사용자가 북마크를 폴더별로 분류할 수 있도록 한다.
 * 폴더 이름은 같은 사용자 내에서 고유해야 한다.
 */
@Entity
@Table(name = "bookmark_folders",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_bookmark_folder_user_name",
               columnNames = {"user_id", "name"}),
       indexes = @Index(name = "idx_bookmark_folder_user", columnList = "user_id"))
@Getter
@NoArgsConstructor
public class BookmarkFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 폴더 이름 (사용자 내 고유) */
    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public BookmarkFolder(User user, String name) {
        this.user      = user;
        this.name      = name;
        this.createdAt = LocalDateTime.now();
    }

    /** 폴더 이름 변경 */
    public void rename(String name) {
        this.name = name;
    }
}
