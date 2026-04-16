package com.effectivedisco.service;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.loadtest.NoOpLoadTestStepProfiler;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PostService(쓰기 전용) 단위 테스트.
 * 읽기 전용 테스트는 {@link PostReadServiceTest}에서 담당한다.
 */
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock PostRepository      postRepository;
    @Mock UserRepository      userRepository;
    @Mock PostLikeRepository  postLikeRepository;
    @Mock TagRepository       tagRepository;
    @Mock TagWriteService     tagWriteService;
    @Mock BoardRepository     boardRepository;
    @Mock NotificationService notificationService;
    @Mock EntityManager       entityManager;
    @Mock UserLookupService   userLookupService;

    PostService postService;

    @BeforeEach
    void setUp() {
        postService = new PostService(
                postRepository,
                userRepository,
                postLikeRepository,
                tagRepository,
                tagWriteService,
                boardRepository,
                notificationService,
                new NoOpLoadTestStepProfiler(),
                entityManager,
                userLookupService
        );
    }

    // ── createPost ───────────────────────────────────────

    @Test
    void createPost_success_savesAndReturnsResponse() {
        User user = makeUser("author");
        ReflectionTestUtils.setField(user, "id", 11L);
        PostRequest request = makePostRequest("New Title", "New Content");
        Post savedPost = makePost(1L, "New Title", "New Content", user);

        given(userRepository.findPostCreateAuthorSnapshotByUsername("author"))
                .willReturn(Optional.of(postCreateAuthorSnapshot(11L, "author")));
        given(entityManager.getReference(User.class, 11L)).willReturn(user);
        given(postRepository.save(any(Post.class))).willReturn(savedPost);

        PostResponse response = postService.createPost(request, "author");

        verify(postRepository).save(any(Post.class));
        assertThat(response.getTitle()).isEqualTo("New Title");
        assertThat(response.getAuthor()).isEqualTo("author");
    }

    @Test
    void createPost_unknownUser_throwsException() {
        given(userRepository.findPostCreateAuthorSnapshotByUsername("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> postService.createPost(makePostRequest("t", "c"), "ghost"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void createPost_draftFlagTrue_savesPostAsDraft() {
        User user = makeUser("author");
        ReflectionTestUtils.setField(user, "id", 11L);
        PostRequest request = makePostRequest("Draft Title", "Draft Content");
        request.setDraft(true); // 초안 플래그 설정

        given(userRepository.findPostCreateAuthorSnapshotByUsername("author"))
                .willReturn(Optional.of(postCreateAuthorSnapshot(11L, "author")));
        given(entityManager.getReference(User.class, 11L)).willReturn(user);
        // save()에 전달된 Post 객체를 그대로 반환해 draft 플래그를 검증한다
        given(postRepository.save(any(Post.class))).willAnswer(inv -> inv.getArgument(0));

        PostResponse response = postService.createPost(request, "author");

        // 초안 플래그가 true여야 한다
        assertThat(response.isDraft()).isTrue();
    }

    // ── updatePost ───────────────────────────────────────

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

    // ── deletePost ───────────────────────────────────────

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

    // ── like / unlike ────────────────────────────────────

    @Test
    void likePost_whenNotLiked_createsLikeAndReturnsLatestCount() {
        User user = makeUser("alice");
        Post post = makePost(1L, "Title", "Content", makeUser("author"));

        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userLookupService.findByUsernameForUpdate("alice")).willReturn(user);
        given(postLikeRepository.existsByPostAndUser(post, user)).willReturn(false);
        given(postRepository.findLikeCountById(1L)).willReturn(1L);

        var response = postService.likePost(1L, "alice");

        assertThat(response.isLiked()).isTrue();
        assertThat(response.getLikeCount()).isEqualTo(1L);
        verify(postLikeRepository).save(any());
        verify(postRepository).incrementLikeCount(1L);
        verify(notificationService).notifyLike(post, "alice");
    }

    @Test
    void likePost_whenAlreadyLiked_isIdempotent() {
        User user = makeUser("alice");
        Post post = makePost(1L, "Title", "Content", makeUser("author"));

        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userLookupService.findByUsernameForUpdate("alice")).willReturn(user);
        given(postLikeRepository.existsByPostAndUser(post, user)).willReturn(true);
        given(postRepository.findLikeCountById(1L)).willReturn(1L);

        var response = postService.likePost(1L, "alice");

        assertThat(response.isLiked()).isTrue();
        assertThat(response.getLikeCount()).isEqualTo(1L);
        verify(postLikeRepository, never()).save(any());
        verify(postRepository, never()).incrementLikeCount(1L);
        verify(notificationService, never()).notifyLike(any(), any());
    }

    @Test
    void unlikePost_whenLiked_deletesLikeAndReturnsLatestCount() {
        User user = makeUser("alice");
        Post post = makePost(1L, "Title", "Content", makeUser("author"));

        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userLookupService.findByUsernameForUpdate("alice")).willReturn(user);
        given(postLikeRepository.deleteByPostAndUser(post, user)).willReturn(1L);
        given(postRepository.findLikeCountById(1L)).willReturn(0L);

        var response = postService.unlikePost(1L, "alice");

        assertThat(response.isLiked()).isFalse();
        assertThat(response.getLikeCount()).isZero();
        verify(postRepository).decrementLikeCount(1L);
    }

    @Test
    void unlikePost_whenAlreadyUnliked_isIdempotent() {
        User user = makeUser("alice");
        Post post = makePost(1L, "Title", "Content", makeUser("author"));

        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userLookupService.findByUsernameForUpdate("alice")).willReturn(user);
        given(postLikeRepository.deleteByPostAndUser(post, user)).willReturn(0L);
        given(postRepository.findLikeCountById(1L)).willReturn(0L);

        var response = postService.unlikePost(1L, "alice");

        assertThat(response.isLiked()).isFalse();
        assertThat(response.getLikeCount()).isZero();
        verify(postRepository, never()).decrementLikeCount(1L);
    }

    // ── adminPinToggle ───────────────────────────────────

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

    // ── publishDraft ─────────────────────────────────────

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

    private PostRequest makePostRequest(String title, String content) {
        PostRequest req = new PostRequest();
        req.setTitle(title);
        req.setContent(content);
        return req;
    }

    private UserRepository.PostCreateAuthorSnapshot postCreateAuthorSnapshot(Long id, String username) {
        return new UserRepository.PostCreateAuthorSnapshot() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getUsername() {
                return username;
            }
        };
    }
}
