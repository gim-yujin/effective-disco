package com.effectivedisco.repository;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.CommentLike;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentLikeRepository extends JpaRepository<CommentLike, Long> {

    /**
     * 댓글 좋아요를 단일 bulk DELETE 로 제거한다.
     *
     * <p>파생 delete 는 엔티티 로드 → {@code em.remove()} 순서로 동작해 동시 호출 시
     * 두 번째 호출에서 {@code StaleObjectState} 가 발생한다. bulk DELETE 는 0건/1건 모두
     * 정상 처리되므로 lock 없이 idempotent 삭제가 가능하다 (PostLikeRepository 와 동일 패턴).
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM CommentLike cl WHERE cl.comment = :comment AND cl.user = :user")
    long deleteByCommentAndUser(@Param("comment") Comment comment, @Param("user") User user);

    /** 특정 댓글의 좋아요 수 (관리/프로필용) */
    long countByComment(Comment comment);

    /** 회원 탈퇴: 이 사용자가 누른 댓글 좋아요 전체 삭제 */
    void deleteByUser(User user);

    /** 회원 탈퇴: 이 사용자의 댓글에 달린 좋아요 전체 삭제 (댓글 cascade 전 선행 삭제 필요) */
    @Modifying
    @Query("DELETE FROM CommentLike cl WHERE cl.comment.author = :user")
    void deleteByCommentAuthor(@Param("user") User user);
}
