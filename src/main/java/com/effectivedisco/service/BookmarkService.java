package com.effectivedisco.service;

import com.effectivedisco.domain.Bookmark;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BookmarkRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final PostRepository     postRepository;
    private final PostLikeRepository postLikeRepository;
    private final UserRepository     userRepository;

    /**
     * 북마크 토글.
     * 이미 북마크된 경우 제거, 아닌 경우 추가.
     *
     * @return true면 북마크 추가, false면 북마크 제거
     */
    @Transactional
    public boolean toggle(String username, Long postId) {
        User user = findUser(username);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + postId));

        if (bookmarkRepository.existsByUserAndPost(user, post)) {
            bookmarkRepository.deleteByUserAndPost(user, post);
            return false;
        } else {
            bookmarkRepository.save(new Bookmark(user, post));
            return true;
        }
    }

    /** 현재 사용자가 해당 게시물을 북마크했는지 여부 */
    public boolean isBookmarked(String username, Long postId) {
        User user = findUser(username);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + postId));
        return bookmarkRepository.existsByUserAndPost(user, post);
    }

    /** 사용자의 북마크 목록을 최신순으로 반환 */
    public List<PostResponse> getBookmarks(String username) {
        User user = findUser(username);
        return bookmarkRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(b -> new PostResponse(b.getPost(),
                        postLikeRepository.countByPost(b.getPost())))
                .toList();
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }
}
