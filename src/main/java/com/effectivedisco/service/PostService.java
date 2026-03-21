package com.effectivedisco.service;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;

    public Page<PostResponse> getPosts(int page, int size, String keyword) {
        PageRequest pageable = PageRequest.of(page, size);
        if (keyword != null && !keyword.isBlank()) {
            return postRepository.searchByKeyword(keyword, pageable).map(PostResponse::new);
        }
        return postRepository.findAllByOrderByCreatedAtDesc(pageable).map(PostResponse::new);
    }

    public PostResponse getPost(Long id) {
        return new PostResponse(findPost(id));
    }

    @Transactional
    public PostResponse createPost(PostRequest request, String username) {
        User user = findUser(username);
        Post post = Post.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .author(user)
                .build();
        return new PostResponse(postRepository.save(post));
    }

    @Transactional
    public PostResponse updatePost(Long id, PostRequest request, String username) {
        Post post = findPost(id);
        checkOwnership(post.getAuthor().getUsername(), username);
        post.update(request.getTitle(), request.getContent());
        return new PostResponse(post);
    }

    @Transactional
    public void deletePost(Long id, String username) {
        Post post = findPost(id);
        checkOwnership(post.getAuthor().getUsername(), username);
        postRepository.delete(post);
    }

    private Post findPost(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + id));
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private void checkOwnership(String ownerUsername, String requestUsername) {
        if (!ownerUsername.equals(requestUsername)) {
            throw new AccessDeniedException("No permission to modify this resource");
        }
    }
}
