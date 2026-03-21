package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.CommentRequest;
import com.effectivedisco.dto.response.CommentResponse;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @Mock CommentRepository commentRepository;
    @Mock PostRepository postRepository;
    @Mock UserRepository userRepository;

    @InjectMocks CommentService commentService;

    @Test
    void createComment_success_returnsResponse() {
        Post post = makePost(1L, makeUser("author"));
        User commenter = makeUser("commenter");
        Comment saved = makeComment(1L, "hello", post, commenter, null);

        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userRepository.findByUsername("commenter")).willReturn(Optional.of(commenter));
        given(commentRepository.save(any(Comment.class))).willReturn(saved);

        CommentResponse response = commentService.createComment(1L, makeRequest("hello"), "commenter");

        assertThat(response.getContent()).isEqualTo("hello");
        assertThat(response.getAuthor()).isEqualTo("commenter");
        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    void createComment_postNotFound_throwsException() {
        given(postRepository.findById(99L)).willReturn(Optional.empty());

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
        given(userRepository.findByUsername("replier")).willReturn(Optional.of(replier));
        given(commentRepository.save(any(Comment.class))).willReturn(saved);

        CommentResponse response = commentService.createReply(1L, 10L, makeRequest("reply"), "replier");

        assertThat(response.getContent()).isEqualTo("reply");
    }

    @Test
    void createReply_parentIsReply_throwsException() {
        Post post = makePost(1L, makeUser("author"));
        Comment grandParent = makeComment(9L, "root", post, makeUser("u"), null);
        Comment parent = makeComment(10L, "reply", post, makeUser("u"), grandParent); // 이미 대댓글

        given(postRepository.findById(1L)).willReturn(Optional.of(post));
        given(userRepository.findByUsername("user")).willReturn(Optional.of(makeUser("user")));
        given(commentRepository.findById(10L)).willReturn(Optional.of(parent));

        assertThatThrownBy(() -> commentService.createReply(1L, 10L, makeRequest("nested"), "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대댓글에는 답글을 달 수 없습니다");
    }

    @Test
    void createReply_commentBelongsToDifferentPost_throwsException() {
        Post post1 = makePost(1L, makeUser("author"));
        Post post2 = makePost(2L, makeUser("author"));
        Comment parent = makeComment(10L, "comment", post2, makeUser("u"), null); // post2 소속

        given(postRepository.findById(1L)).willReturn(Optional.of(post1));
        given(userRepository.findByUsername("user")).willReturn(Optional.of(makeUser("user")));
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
}
