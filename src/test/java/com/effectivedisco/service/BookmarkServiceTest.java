package com.effectivedisco.service;

import com.effectivedisco.domain.Bookmark;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BookmarkRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class BookmarkServiceTest {

    @Mock BookmarkRepository bookmarkRepository;
    @Mock PostRepository     postRepository;
    @Mock PostLikeRepository postLikeRepository;
    @Mock UserRepository     userRepository;

    @InjectMocks BookmarkService bookmarkService;

    // ── toggle ────────────────────────────────────────────────

    @Test
    void toggle_notBookmarked_savesAndReturnsTrue() {
        User user = makeUser("alice");
        Post post = makePost(1L, user);
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(bookmarkRepository.existsByUserAndPost(user, post)).willReturn(false);

        boolean result = bookmarkService.toggle("alice", 1L);

        assertThat(result).isTrue();
        verify(bookmarkRepository).save(any(Bookmark.class));
    }

    @Test
    void toggle_alreadyBookmarked_deletesAndReturnsFalse() {
        User user = makeUser("alice");
        Post post = makePost(1L, user);
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(bookmarkRepository.existsByUserAndPost(user, post)).willReturn(true);

        boolean result = bookmarkService.toggle("alice", 1L);

        assertThat(result).isFalse();
        verify(bookmarkRepository).deleteByUserAndPost(user, post);
        verify(bookmarkRepository, never()).save(any());
    }

    @Test
    void toggle_postNotFound_throwsException() {
        User user = makeUser("alice");
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bookmarkService.toggle("alice", 99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── isBookmarked ──────────────────────────────────────────

    @Test
    void isBookmarked_whenExists_returnsTrue() {
        User user = makeUser("alice");
        Post post = makePost(1L, user);
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(bookmarkRepository.existsByUserAndPost(user, post)).willReturn(true);

        assertThat(bookmarkService.isBookmarked("alice", 1L)).isTrue();
    }

    @Test
    void isBookmarked_whenAbsent_returnsFalse() {
        User user = makeUser("alice");
        Post post = makePost(1L, user);
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(bookmarkRepository.existsByUserAndPost(user, post)).willReturn(false);

        assertThat(bookmarkService.isBookmarked("alice", 1L)).isFalse();
    }

    // ── getBookmarks ──────────────────────────────────────────

    @Test
    void getBookmarks_returnsPostResponseList() {
        User user = makeUser("alice");
        Post post = makePost(1L, user);
        Bookmark bookmark = new Bookmark(user, post);
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(bookmarkRepository.findByUserOrderByCreatedAtDesc(user)).willReturn(List.of(bookmark));
        given(postLikeRepository.countByPost(post)).willReturn(3L);

        List<PostResponse> result = bookmarkService.getBookmarks("alice");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Post 1");
        assertThat(result.get(0).getLikeCount()).isEqualTo(3L);
    }

    // ── helpers ───────────────────────────────────────────────

    private User makeUser(String username) {
        return User.builder().username(username).email(username + "@test.com").password("pw").build();
    }

    private Post makePost(Long id, User author) {
        Post post = Post.builder().title("Post " + id).content("Content").author(author).build();
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }
}
