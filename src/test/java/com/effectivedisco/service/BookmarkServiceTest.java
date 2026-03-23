package com.effectivedisco.service;

import com.effectivedisco.domain.Bookmark;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BookmarkRepository;
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
    @Mock UserRepository     userRepository;

    @InjectMocks BookmarkService bookmarkService;

    // ── bookmark / unbookmark ─────────────────────────────────

    @Test
    void bookmark_notBookmarked_saves() {
        User user = makeUser("alice");
        Post post = makePost(1L, user);
        given(userRepository.findByUsernameForUpdate("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(bookmarkRepository.existsByUserAndPost(user, post)).willReturn(false);

        bookmarkService.bookmark("alice", 1L);

        verify(bookmarkRepository).save(any(Bookmark.class));
    }

    @Test
    void bookmark_alreadyBookmarked_isIdempotent() {
        User user = makeUser("alice");
        Post post = makePost(1L, user);
        given(userRepository.findByUsernameForUpdate("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(bookmarkRepository.existsByUserAndPost(user, post)).willReturn(true);

        bookmarkService.bookmark("alice", 1L);

        verify(bookmarkRepository, never()).save(any());
    }

    @Test
    void bookmark_postNotFound_throwsException() {
        User user = makeUser("alice");
        given(userRepository.findByUsernameForUpdate("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bookmarkService.bookmark("alice", 99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unbookmark_existingBookmark_deletesRelation() {
        User user = makeUser("alice");
        Post post = makePost(1L, user);
        given(userRepository.findByUsernameForUpdate("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(bookmarkRepository.deleteByUserAndPost(user, post)).willReturn(1L);

        bookmarkService.unbookmark("alice", 1L);

        verify(bookmarkRepository).deleteByUserAndPost(user, post);
    }

    @Test
    void unbookmark_missingBookmark_isIdempotent() {
        User user = makeUser("alice");
        Post post = makePost(1L, user);
        given(userRepository.findByUsernameForUpdate("alice")).willReturn(Optional.of(user));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(bookmarkRepository.deleteByUserAndPost(user, post)).willReturn(0L);

        bookmarkService.unbookmark("alice", 1L);

        verify(bookmarkRepository).deleteByUserAndPost(user, post);
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

        List<PostResponse> result = bookmarkService.getBookmarks("alice");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Post 1");
        assertThat(result.get(0).getLikeCount()).isEqualTo(0L);
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
