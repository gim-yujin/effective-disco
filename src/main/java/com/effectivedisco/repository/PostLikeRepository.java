package com.effectivedisco.repository;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostLike;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    boolean existsByPostAndUser(Post post, User user);

    /**
     * 좋아요를 단일 bulk DELETE로 제거한다.
     *
     * <p>파생 delete는 엔티티 로드 → {@code em.remove()} 순서로 동작해 동시 호출 시
     * 두 번째 호출에서 {@code StaleObjectState} 가 발생한다. bulk DELETE는 0건/1건 결과 모두
     * 정상 처리되므로 lock 없이 idempotent 삭제가 가능하다.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM PostLike l WHERE l.post = :post AND l.user = :user")
    long deleteByPostAndUser(@Param("post") Post post, @Param("user") User user);

    /** 특정 게시물의 좋아요 수 */
    long countByPost(Post post);

    /** 특정 사용자의 특정 게시물 좋아요 수 (0 또는 1) */
    long countByPostAndUser(Post post, User user);

    /**
     * 특정 사용자가 작성한 모든 게시물에 달린 좋아요 총합.
     * "받은 좋아요 수" 통계로 프로필 페이지에 표시한다.
     */
    @Query("SELECT COUNT(l) FROM PostLike l WHERE l.post.author = :user")
    long countLikesReceivedByUser(@Param("user") User user);

    /** 회원 탈퇴: 이 사용자가 누른 좋아요 전체 삭제 */
    void deleteByUser(User user);

    /** 회원 탈퇴: 이 사용자의 게시물에 달린 좋아요 전체 삭제 (cascade 전 선행 삭제 필요) */
    @Modifying
    @Query("DELETE FROM PostLike l WHERE l.post.author = :user")
    void deleteByPostAuthor(@Param("user") User user);
}
