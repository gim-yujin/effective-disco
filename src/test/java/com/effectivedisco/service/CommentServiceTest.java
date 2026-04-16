package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.CommentRequest;
import com.effectivedisco.dto.response.CommentResponse;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock CommentRepository   commentRepository;
    @Mock PostRepository      postRepository;
    @Mock UserRepository      userRepository;
    @Mock NotificationService notificationService;
    @Mock EntityManager       entityManager;
    @Mock UserLookupService   userLookupService;

    CommentService commentService;

    @BeforeEach
    void setUp() {
        // 기본 최대 깊이 2로 설정
        commentService = new CommentService(
                commentRepository, postRepository, userRepository,
                notificationService, entityManager, userLookupService, 2
        );
    }

    @Test
    void createComment_success_returnsResponse() {
        Post post = makePost(1L, makeUser("author"));
        User commenter = makeUser("commenter");
        Comment saved = makeComment(1L, "hello", post, commenter, null);
        ReflectionTestUtils.setField(commenter, "id", 21L);

        given(postRepository.findCommentNotificationTargetById(1L))
                .willReturn(Optional.of(commentNotificationTarget(1L, "author")));
        given(userRepository.findCommentAuthorSnapshotByUsername("commenter"))
                .willReturn(Optional.of(commentAuthorSnapshot(21L, "commenter", null)));
        given(entityManager.getReference(Post.class, 1L)).willReturn(post);
        given(entityManager.getReference(User.class, 21L)).willReturn(commenter);
        given(commentRepository.save(any(Comment.class))).willReturn(saved);

        CommentResponse response = commentService.createComment(1L, makeRequest("hello"), "commenter");

        assertThat(response.getContent()).isEqualTo("hello");
        assertThat(response.getAuthor()).isEqualTo("commenter");
        verify(commentRepository).save(any(Comment.class));
        verify(notificationService).notifyComment("author", 1L, "commenter");
    }

    @Test
    void createComment_postNotFound_throwsException() {
        given(postRepository.findCommentNotificationTargetById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.createComment(99L, makeRequest("hi"), "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Post not found");
    }

    @Test
    void createReply_success_setsParent() {
        Post post = makePost(1L, makeUser("author"));
        User replier = makeUser("replier");
        Comment parent = makeComment(10L, "parent comment", post, makeUser("commenter"), null);
        Comment saved = makeComment(11L, "reply", post, replier, parent);

        given(commentRepository.findById(10L)).willReturn(Optional.of(parent));
        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userLookupService.findByUsername("replier")).willReturn(replier);
        given(commentRepository.save(any(Comment.class))).willReturn(saved);

        CommentResponse response = commentService.createReply(1L, 10L, makeRequest("reply"), "replier");

        assertThat(response.getContent()).isEqualTo("reply");
    }

    @Test
    void createReply_exceedsMaxDepth_throwsException() {
        // maxDepth=2인 상태에서 depth=2인 부모에 답글을 달면 거부되어야 한다
        Post post = makePost(1L, makeUser("author"));
        Comment deepParent = makeComment(10L, "deep reply", post, makeUser("u"), null);
        ReflectionTestUtils.setField(deepParent, "depth", 2); // 이미 최대 깊이

        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userLookupService.findByUsername("user")).willReturn(makeUser("user"));
        given(commentRepository.findById(10L)).willReturn(Optional.of(deepParent));

        assertThatThrownBy(() -> commentService.createReply(1L, 10L, makeRequest("nested"), "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최대 댓글 깊이");
    }

    @Test
    void createReply_commentBelongsToDifferentPost_throwsException() {
        Post post1 = makePost(1L, makeUser("author"));
        Post post2 = makePost(2L, makeUser("author"));
        Comment parent = makeComment(10L, "comment", post2, makeUser("u"), null); // post2 소속

        given(postRepository.findById(1L)).willReturn(Optional.of(post1));
        given(userLookupService.findByUsername("user")).willReturn(makeUser("user"));
        given(commentRepository.findById(10L)).willReturn(Optional.of(parent));

        assertThatThrownBy(() -> commentService.createReply(1L, 10L, makeRequest("reply"), "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Comment does not belong to the post");
    }

    @Test
    void updateComment_notOwner_throwsAccessDeniedException() {
        Post post = makePost(1L, makeUser("author"));
        Comment comment = makeComment(10L, "content", post, makeUser("owner"), null);

        given(commentRepository.findById(10L)).willReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.updateComment(1L, 10L, makeRequest("new"), "other"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deleteComment_notFound_throwsException() {
        given(commentRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.deleteComment(1L, 99L, "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Comment not found");
    }

    @Test
    void deleteComment_success_callsDelete() {
        Post post = makePost(1L, makeUser("author"));
        User owner = makeUser("owner");
        Comment comment = makeComment(10L, "content", post, owner, null);

        given(commentRepository.findById(10L)).willReturn(Optional.of(comment));

        commentService.deleteComment(1L, 10L, "owner");

        verify(commentRepository).delete(comment);
    }

    @Test
    void deleteComment_commentBelongsToDifferentPost_throwsException() {
        Post post1 = makePost(1L, makeUser("author"));
        Post post2 = makePost(2L, makeUser("author"));
        Comment comment = makeComment(10L, "content", post2, makeUser("owner"), null);

        given(commentRepository.findById(10L)).willReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.deleteComment(1L, 10L, "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Comment does not belong to the post");
    }

    // --- helpers ---

    private User makeUser(String username) {
        return User.builder().username(username).email(username + "@t.com").password("pw").build();
    }

    private Post makePost(Long id, User author) {
        Post post = Post.builder().title("title").content("content").author(author).build();
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    private Comment makeComment(Long id, String content, Post post, User author, Comment parent) {
        Comment comment = Comment.builder().content(content).post(post).author(author).parent(parent).build();
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }

    private CommentRequest makeRequest(String content) {
        CommentRequest req = new CommentRequest();
        req.setContent(content);
        return req;
    }

    private PostRepository.CommentNotificationTarget commentNotificationTarget(Long id, String authorUsername) {
        return new PostRepository.CommentNotificationTarget() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getAuthorUsername() {
                return authorUsername;
            }
        };
    }

    private UserRepository.CommentAuthorSnapshot commentAuthorSnapshot(Long id,
                                                                      String username,
                                                                      String profileImageUrl) {
        return new UserRepository.CommentAuthorSnapshot() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public String getProfileImageUrl() {
                return profileImageUrl;
            }
        };
    }
}
