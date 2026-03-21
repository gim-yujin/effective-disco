package com.effectivedisco.service;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostLike;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.LikeResponse;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.PostLikeRepository;
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
    private final PostLikeRepository postLikeRepository;

    public Page<PostResponse> getPosts(int page, int size, String keyword) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<Post> posts = (keyword != null && !keyword.isBlank())
                ? postRepository.searchByKeyword(keyword, pageable)
                : postRepository.findAllByOrderByCreatedAtDesc(pageable);
        return posts.map(post -> new PostResponse(post, postLikeRepository.countByPost(post)));
    }

    public PostResponse getPost(Long id) {
        Post post = findPost(id);
        return new PostResponse(post, postLikeRepository.countByPost(post));
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
        return new PostResponse(post, postLikeRepository.countByPost(post));
    }

    @Transactional
    public void deletePost(Long id, String username) {
        Post post = findPost(id);
        checkOwnership(post.getAuthor().getUsername(), username);
        postRepository.delete(post);
    }

    @Transactional
    public LikeResponse toggleLike(Long postId, String username) {
        Post post = findPost(postId);
        User user = findUser(username);
        if (postLikeRepository.existsByPostAndUser(post, user)) {
            postLikeRepository.deleteByPostAndUser(post, user);
        } else {
            postLikeRepository.save(new PostLike(post, user));
        }
        long count = postLikeRepository.countByPost(post);
        boolean liked = postLikeRepository.existsByPostAndUser(post, user);
        return new LikeResponse(liked, count);
    }

    public boolean isLikedByUser(Long postId, String username) {
        Post post = findPost(postId);
        User user = findUser(username);
        return postLikeRepository.existsByPostAndUser(post, user);
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
