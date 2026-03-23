package com.effectivedisco.service;

import com.effectivedisco.domain.Bookmark;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BookmarkRepository;
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
    private final UserRepository     userRepository;

    /**
     * 게시물을 북마크한다.
     * 이미 북마크된 상태이면 그대로 성공 처리한다.
     */
    @Transactional
    public void bookmark(String username, Long postId) {
        User user = findUserForUpdate(username);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + postId));

        // 문제 해결:
        // 토글을 "북마크 상태 보장"으로 바꾸고 요청 주체 User를 잠그면
        // 같은 북마크 요청이 중복 도착해도 중복 row 생성 경쟁이 발생하지 않는다.
        if (!bookmarkRepository.existsByUserAndPost(user, post)) {
            bookmarkRepository.save(new Bookmark(user, post));
        }
    }

    /**
     * 게시물 북마크를 해제한다.
     * 이미 해제된 상태이면 그대로 성공 처리한다.
     */
    @Transactional
    public void unbookmark(String username, Long postId) {
        User user = findUserForUpdate(username);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + postId));

        // 문제 해결:
        // delete 결과가 0이면 이미 해제된 상태이므로 그대로 no-op로 끝낸다.
        long deleted = bookmarkRepository.deleteByUserAndPost(user, post);
        if (deleted == 0) {
            return;
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
                .map(b -> new PostResponse(b.getPost()))
                .toList();
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    private User findUserForUpdate(String username) {
        return userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }
}
