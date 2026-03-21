package com.effectivedisco.repository;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostLike;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {
    boolean existsByPostAndUser(Post post, User user);
    void deleteByPostAndUser(Post post, User user);
    long countByPost(Post post);
}
