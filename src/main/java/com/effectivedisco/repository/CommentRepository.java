package com.effectivedisco.repository;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    interface CommentViewRow {
        Long getId();
        String getContent();
        LocalDateTime getCreatedAt();
        LocalDateTime getUpdatedAt();
        Long getParentId();
        String getAuthorUsername();
        String getAuthorProfileImageUrl();
    }

    interface TopLevelCommentCursor {
        Long getId();
        LocalDateTime getCreatedAt();
    }

    /**
     * 특정 게시물의 최상위 댓글(대댓글 제외)을 오래된 순으로 조회.
     * parent가 null인 댓글만 반환하며, 각 댓글의 replies(대댓글)는
     * Comment 엔티티의 @OneToMany로 지연 로딩된다.
     */
    List<Comment> findByPostIdAndParentIsNullOrderByCreatedAtAsc(Long postId);

    /**
     * 문제 해결:
     * 게시물 상세는 댓글 수가 많을수록 "최상위 댓글 전체 조회 + 각 댓글 replies/author LAZY 접근" 때문에
     * N+1 과 거대한 서버 렌더링이 동시에 터진다. 최상위 댓글은 projection + Page 로 먼저 자른다.
     */
    @Query(value = """
            SELECT
                c.id AS id,
                c.content AS content,
                c.createdAt AS createdAt,
                c.updatedAt AS updatedAt,
                c.parent.id AS parentId,
                a.username AS authorUsername,
                a.profileImageUrl AS authorProfileImageUrl
            FROM Comment c
            JOIN c.author a
            WHERE c.post.id = :postId
              AND c.parent IS NULL
            ORDER BY c.createdAt ASC, c.id ASC
            """,
            countQuery = """
            SELECT COUNT(c)
            FROM Comment c
            WHERE c.post.id = :postId
              AND c.parent IS NULL
            """)
    Page<CommentViewRow> findTopLevelCommentRowsByPostIdOrderByCreatedAtAsc(@Param("postId") Long postId,
                                                                            Pageable pageable);

    /**
     * 문제 해결:
     * 현재 페이지에 포함된 부모 댓글들의 대댓글은 parent_id IN 한 번으로 배치 조회해야
     * 댓글 수가 커져도 SQL 수가 부모 댓글 개수에 비례해서 늘어나지 않는다.
     */
    @Query("""
            SELECT
                c.id AS id,
                c.content AS content,
                c.createdAt AS createdAt,
                c.updatedAt AS updatedAt,
                c.parent.id AS parentId,
                a.username AS authorUsername,
                a.profileImageUrl AS authorProfileImageUrl
            FROM Comment c
            JOIN c.author a
            WHERE c.parent.id IN :parentIds
            ORDER BY c.parent.id ASC, c.createdAt ASC, c.id ASC
            """)
    List<CommentViewRow> findReplyRowsByParentIdInOrderByCreatedAtAsc(@Param("parentIds") List<Long> parentIds);

    @Query("""
            SELECT COALESCE(c.parent.id, c.id)
            FROM Comment c
            WHERE c.id = :commentId
            """)
    Long findTopLevelCommentIdByCommentId(@Param("commentId") Long commentId);

    @Query("""
            SELECT c.id AS id, c.createdAt AS createdAt
            FROM Comment c
            WHERE c.id = :commentId
              AND c.parent IS NULL
            """)
    TopLevelCommentCursor findTopLevelCommentCursorById(@Param("commentId") Long commentId);

    @Query("""
            SELECT COUNT(c)
            FROM Comment c
            WHERE c.post.id = :postId
              AND c.parent IS NULL
              AND (
                c.createdAt < :createdAt
                OR (c.createdAt = :createdAt AND c.id <= :commentId)
              )
            """)
    long countTopLevelCommentsBeforeOrAt(@Param("postId") Long postId,
                                         @Param("createdAt") LocalDateTime createdAt,
                                         @Param("commentId") Long commentId);

    /** 특정 사용자의 총 댓글·대댓글 수 (프로필 통계 표시용) */
    long countByAuthor(User author);

    long countByPostIdAndParentIsNull(Long postId);
}
