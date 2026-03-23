package com.effectivedisco.repository;

import com.effectivedisco.domain.Bookmark;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    boolean existsByUserAndPost(User user, Post post);

    long deleteByUserAndPost(User user, Post post);

    long countByUserAndPost(User user, Post post);

    /** 북마크 목록 (최신순) */
    List<Bookmark> findByUserOrderByCreatedAtDesc(User user);
}
