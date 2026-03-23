package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import com.effectivedisco.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock PostRepository      postRepository;
    @Mock UserRepository      userRepository;
    @Mock PostLikeRepository  postLikeRepository;
    @Mock TagRepository       tagRepository;
    @Mock BoardRepository     boardRepository;
    @Mock NotificationService notificationService;

    @InjectMocks PostService postService;

    @Test
    void getPost_success_returnsPostResponse() {
        Post post = makePost(1L, "Title", "Content", makeUser("author"));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        PostResponse response = postService.getPost(1L);

        assertThat(response.getTitle()).isEqualTo("Title");
        assertThat(response.getAuthor()).isEqualTo("author");
    }

    @Test
    void getPost_notFound_throwsException() {
        given(postRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> postService.getPost(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("게시물을 찾을 수 없습니다");
    }

    @Test
    void createPost_success_savesAndReturnsResponse() {
        User user = makeUser("author");
        PostRequest request = makePostRequest("New Title", "New Content");
        Post savedPost = makePost(1L, "New Title", "New Content", user);

        given(userRepository.findByUsername("author")).willReturn(Optional.of(user));
        given(postRepository.save(any(Post.class))).willReturn(savedPost);

        PostResponse response = postService.createPost(request, "author");

        verify(postRepository).save(any(Post.class));
        assertThat(response.getTitle()).isEqualTo("New Title");
        assertThat(response.getAuthor()).isEqualTo("author");
    }

    @Test
    void createPost_unknownUser_throwsException() {
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> postService.createPost(makePostRequest("t", "c"), "ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void updatePost_success_updatesFields() {
        User author = makeUser("author");
        Post post = makePost(1L, "Old Title", "Old Content", author);
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        PostResponse response = postService.updatePost(1L, makePostRequest("New Title", "New Content"), "author");

        assertThat(response.getTitle()).isEqualTo("New Title");
        assertThat(response.getContent()).isEqualTo("New Content");
    }

    @Test
    void updatePost_notOwner_throwsAccessDeniedException() {
        Post post = makePost(1L, "Title", "Content", makeUser("author"));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.updatePost(1L, makePostRequest("t", "c"), "other"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deletePost_success_callsRepositoryDelete() {
        User author = makeUser("author");
        Post post = makePost(1L, "Title", "Content", author);
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        postService.deletePost(1L, "author");

        verify(postRepository).delete(post);
    }

    @Test
    void deletePost_notOwner_throwsAccessDeniedException() {
        Post post = makePost(1L, "Title", "Content", makeUser("author"));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.deletePost(1L, "other"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── adminPinToggle ────────────────────────────────────────

    @Test
    void adminPinToggle_unpinnedPost_pinsItAndReturnsTrue() {
        Post post = makePost(1L, "Title", "Content", makeUser("author"));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        boolean result = postService.adminPinToggle(1L);

        assertThat(result).isTrue();
        assertThat(post.isPinned()).isTrue();
    }

    @Test
    void adminPinToggle_pinnedPost_unpinsItAndReturnsFalse() {
        Post post = makePost(1L, "Title", "Content", makeUser("author"));
        ReflectionTestUtils.setField(post, "pinned", true);
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        boolean result = postService.adminPinToggle(1L);

        assertThat(result).isFalse();
        assertThat(post.isPinned()).isFalse();
    }

    // ── getPinnedPosts ────────────────────────────────────────

    @Test
    void getPinnedPosts_validBoard_returnsMappedList() {
        Board board = makeBoard("free");
        User author = makeUser("author");
        Post post = makePost(1L, "Pinned", "Content", author);
        ReflectionTestUtils.setField(post, "pinned", true);

        given(boardRepository.findBySlug("free")).willReturn(Optional.of(board));
        given(postRepository.findByBoardAndPinnedTrueAndDraftFalseOrderByCreatedAtDesc(board))
                .willReturn(List.of(post));
        List<PostResponse> result = postService.getPinnedPosts("free");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Pinned");
    }

    @Test
    void getPinnedPosts_nullSlug_returnsEmptyList() {
        assertThat(postService.getPinnedPosts(null)).isEmpty();
    }

    @Test
    void getPinnedPosts_unknownBoard_returnsEmptyList() {
        given(boardRepository.findBySlug("unknown")).willReturn(Optional.empty());

        assertThat(postService.getPinnedPosts("unknown")).isEmpty();
    }

    // ── draft 생성 ────────────────────────────────────────

    @Test
    void createPost_draftFlagTrue_savesPostAsDraft() {
        User user = makeUser("author");
        PostRequest request = makePostRequest("Draft Title", "Draft Content");
        request.setDraft(true); // 초안 플래그 설정

        given(userRepository.findByUsername("author")).willReturn(Optional.of(user));
        // save()에 전달된 Post 객체를 그대로 반환해 draft 플래그를 검증한다
        given(postRepository.save(any(Post.class))).willAnswer(inv -> inv.getArgument(0));

        PostResponse response = postService.createPost(request, "author");

        // 초안 플래그가 true여야 한다
        assertThat(response.isDraft()).isTrue();
    }

    // ── draft → 발행 전환 ─────────────────────────────────

    @Test
    void updatePost_draftFlagFalse_publishesPost() {
        User author = makeUser("author");
        Post post   = makePost(1L, "Old Title", "Old Content", author);
        // 게시물을 초안 상태로 설정
        ReflectionTestUtils.setField(post, "draft", true);

        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        PostRequest request = makePostRequest("New Title", "New Content");
        request.setDraft(false); // 공개 저장 → publish() 호출

        PostResponse response = postService.updatePost(1L, request, "author");

        // 발행 후 draft가 false여야 한다
        assertThat(response.isDraft()).isFalse();
        assertThat(response.getTitle()).isEqualTo("New Title");
    }

    // ── getDrafts ─────────────────────────────────────────

    @Test
    void getDrafts_returnsDraftPostsForUser() {
        User author = makeUser("author");
        Post draft  = makePost(1L, "My Draft", "Content", author);
        ReflectionTestUtils.setField(draft, "draft", true);

        given(userRepository.findByUsername("author")).willReturn(Optional.of(author));
        given(postRepository.findByAuthorAndDraftTrueOrderByCreatedAtDesc(
                any(User.class), any())).willReturn(new PageImpl<>(List.of(draft)));

        Page<PostResponse> result = postService.getDrafts("author", 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("My Draft");
        assertThat(result.getContent().get(0).isDraft()).isTrue();
    }

    // ── publishDraft ──────────────────────────────────────

    @Test
    void publishDraft_success_publishesPost() {
        User author = makeUser("author");
        Post post   = makePost(1L, "Title", "Content", author);
        // 발행 전 초안 상태
        ReflectionTestUtils.setField(post, "draft", true);

        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        postService.publishDraft(1L, "author");

        // publish() 호출로 draft가 false로 전환되어야 한다
        assertThat(post.isDraft()).isFalse();
    }

    @Test
    void publishDraft_notOwner_throwsAccessDeniedException() {
        Post post = makePost(1L, "Title", "Content", makeUser("author"));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.publishDraft(1L, "other"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- helpers ---

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

    private PostRequest makePostRequest(String title, String content) {
        PostRequest req = new PostRequest();
        req.setTitle(title);
        req.setContent(content);
        return req;
    }

    private Board makeBoard(String slug) {
        return Board.builder().name(slug).slug(slug).build();
    }
}
