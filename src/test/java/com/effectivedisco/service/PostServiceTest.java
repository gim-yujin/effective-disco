package com.effectivedisco.service;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock PostRepository     postRepository;
    @Mock UserRepository     userRepository;
    @Mock PostLikeRepository postLikeRepository;
    @Mock TagRepository      tagRepository;
    @Mock BoardRepository    boardRepository; // PostService의 resolveBoard()가 사용

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
}
