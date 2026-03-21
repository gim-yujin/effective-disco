package com.effectivedisco.repository;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 특정 게시물의 최상위 댓글(대댓글 제외)을 오래된 순으로 조회.
     * parent가 null인 댓글만 반환하며, 각 댓글의 replies(대댓글)는
     * Comment 엔티티의 @OneToMany로 지연 로딩된다.
     */
    List<Comment> findByPostIdAndParentIsNullOrderByCreatedAtAsc(Long postId);

    /** 특정 사용자의 총 댓글·대댓글 수 (프로필 통계 표시용) */
    long countByAuthor(User author);
}
