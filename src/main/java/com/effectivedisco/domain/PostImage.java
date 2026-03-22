package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 게시물 첨부 이미지 엔티티.
 *
 * 한 게시물에 여러 장의 이미지를 첨부할 수 있도록 별도 테이블로 분리한다.
 * sortOrder(정렬 순서)로 업로드 순서를 보존하고, 갤러리를 그 순서대로 표시한다.
 *
 * 삭제 정책:
 * - 게시물 삭제 시 DB 레벨 FK ON DELETE CASCADE(@OnDelete)로 자동 삭제.
 * - Post.images 컬렉션에 CascadeType.ALL + orphanRemoval = true 도 설정하므로
 *   JPA 레벨에서도 고아 이미지가 자동 제거된다.
 */
@Entity
@Table(name = "post_images")
@Getter
@NoArgsConstructor
public class PostImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이미지가 속한 게시물. 게시물 삭제 시 DB CASCADE 삭제. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Post post;

    /** ImageService.store()가 반환한 서빙 URL (예: /uploads/images/uuid.jpg) */
    @Column(nullable = false)
    private String imageUrl;

    /**
     * 갤러리 표시 순서 (0부터 시작).
     * 업로드 순서를 그대로 유지해 사용자가 의도한 배열을 보존한다.
     */
    @Column(nullable = false)
    private int sortOrder;

    public PostImage(Post post, String imageUrl, int sortOrder) {
        this.post      = post;
        this.imageUrl  = imageUrl;
        this.sortOrder = sortOrder;
    }
}
