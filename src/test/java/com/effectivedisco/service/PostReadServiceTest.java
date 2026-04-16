package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.loadtest.NoOpLoadTestStepProfiler;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * PostReadService(읽기 전용) 단위 테스트.
 * 쓰기 테스트는 {@link PostServiceTest}에서 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class PostReadServiceTest {

    @Mock PostRepository     postRepository;
    @Mock UserRepository     userRepository;
    @Mock PostLikeRepository postLikeRepository;
    @Mock TagRepository      tagRepository;
    @Mock BoardRepository    boardRepository;
    @Mock UserLookupService  userLookupService;

    PostReadService postReadService;

    @BeforeEach
    void setUp() {
        postReadService = new PostReadService(
                postRepository,
                userRepository,
                postLikeRepository,
                tagRepository,
                boardRepository,
                new NoOpLoadTestStepProfiler(),
                userLookupService
        );
    }

    // ── getPost ──────────────────────────────────────────

    @Test
    void getPost_success_returnsPostResponse() {
        Post post = makePost(1L, "Title", "Content", makeUser("author"));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        PostResponse response = postReadService.getPost(1L);

        assertThat(response.getTitle()).isEqualTo("Title");
        assertThat(response.getAuthor()).isEqualTo("author");
    }

    @Test
    void getPost_notFound_throwsException() {
        given(postRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> postReadService.getPost(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("게시물을 찾을 수 없습니다");
    }

    // ── getPinnedPosts ───────────────────────────────────

    @Test
    void getPinnedPosts_validBoard_returnsMappedList() {
        Board board = makeBoard("free");
        User author = makeUser("author");
        Post post = makePost(1L, "Pinned", "Content", author);
        ReflectionTestUtils.setField(post, "pinned", true);

        given(boardRepository.findBySlug("free")).willReturn(Optional.of(board));
        given(postRepository.findByBoardAndPinnedTrueAndDraftFalseOrderByCreatedAtDesc(board))
                .willReturn(List.of(post));
        List<PostResponse> result = postReadService.getPinnedPosts("free");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Pinned");
    }

    @Test
    void getPinnedPosts_nullSlug_returnsEmptyList() {
        assertThat(postReadService.getPinnedPosts(null)).isEmpty();
    }

    @Test
    void getPinnedPosts_unknownBoard_returnsEmptyList() {
        given(boardRepository.findBySlug("unknown")).willReturn(Optional.empty());
        assertThat(postReadService.getPinnedPosts("unknown")).isEmpty();
    }

    // ── getDrafts ────────────────────────────────────────

    @Test
    void getDrafts_returnsDraftPostsForUser() {
        User author = makeUser("author");
        Post draft  = makePost(1L, "My Draft", "Content", author);
        ReflectionTestUtils.setField(draft, "draft", true);

        given(userLookupService.findByUsername("author")).willReturn(author);
        given(postRepository.findByAuthorAndDraftTrueOrderByCreatedAtDesc(
                any(User.class), any())).willReturn(new PageImpl<>(List.of(draft)));

        Page<PostResponse> result = postReadService.getDrafts("author", 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("My Draft");
        assertThat(result.getContent().get(0).isDraft()).isTrue();
    }

    // ── isLikedByUser ────────────────────────────────────

    @Test
    void isLikedByUser_liked_returnsTrue() {
        User user = makeUser("alice");
        Post post = makePost(1L, "Title", "Content", makeUser("author"));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userLookupService.findByUsername("alice")).willReturn(user);
        given(postLikeRepository.existsByPostAndUser(post, user)).willReturn(true);

        assertThat(postReadService.isLikedByUser(1L, "alice")).isTrue();
    }

    @Test
    void isLikedByUser_notLiked_returnsFalse() {
        User user = makeUser("alice");
        Post post = makePost(1L, "Title", "Content", makeUser("author"));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userLookupService.findByUsername("alice")).willReturn(user);
        given(postLikeRepository.existsByPostAndUser(post, user)).willReturn(false);

        assertThat(postReadService.isLikedByUser(1L, "alice")).isFalse();
    }

    // ── getPostsByAuthor ─────────────────────────────────

    @Test
    void getPostsByAuthor_returnsPublicPostsForAuthor() {
        User author = makeUser("alice");
        Post post = makePost(1L, "Post", "Content", author);

        given(userLookupService.findByUsername("alice")).willReturn(author);
        given(postRepository.findByAuthorAndDraftFalseOrderByCreatedAtDesc(any(User.class), any()))
                .willReturn(new PageImpl<>(List.of(post)));

        Page<PostResponse> result = postReadService.getPostsByAuthor("alice", 0, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getAuthor()).isEqualTo("alice");
    }

    // ── helpers ──────────────────────────────────────────

    private User makeUser(String username) {
        return User.builder()
                .username(username)
                .email(username + "@test.com")
                .password("encoded")
                .build();
    }

    private Post makePost(Long id, String title, String content, User author) {
        Post post = Post.builder().title(title).content(content).author(author).build();
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    private Board makeBoard(String slug) {
        return Board.builder().name(slug).slug(slug).build();
    }
}
