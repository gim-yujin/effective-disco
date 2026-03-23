package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.CommentRequest;
import com.effectivedisco.dto.response.CommentResponse;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository   commentRepository;
    private final PostRepository      postRepository;
    private final UserRepository      userRepository;
    private final NotificationService notificationService;

    public List<CommentResponse> getComments(Long postId) {
        return commentRepository.findByPostIdAndParentIsNullOrderByCreatedAtAsc(postId)
                .stream().map(CommentResponse::new).toList();
    }

    @Transactional
    public CommentResponse createComment(Long postId, CommentRequest request, String username) {
        Post post = findPost(postId);
        User user = findUser(username);
        Comment comment = Comment.builder()
                .content(request.getContent())
                .post(post)
                .author(user)
                .build();
        CommentResponse response = new CommentResponse(commentRepository.save(comment));
        postRepository.incrementCommentCount(postId);
        // 게시물 작성자에게 댓글 알림 (본인 댓글은 제외)
        notificationService.notifyComment(post, username);
        return response;
    }

    @Transactional
    public CommentResponse createReply(Long postId, Long parentCommentId, CommentRequest request, String username) {
        Post post = findPost(postId);
        User user = findUser(username);
        Comment parent = commentRepository.findById(parentCommentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + parentCommentId));
        if (!parent.getPost().getId().equals(postId)) {
            throw new IllegalArgumentException("Comment does not belong to the post");
        }
        if (parent.isReply()) {
            throw new IllegalArgumentException("대댓글에는 답글을 달 수 없습니다");
        }
        Comment reply = Comment.builder()
                .content(request.getContent())
                .post(post)
                .author(user)
                .parent(parent)
                .build();
        CommentResponse response = new CommentResponse(commentRepository.save(reply));
        postRepository.incrementCommentCount(postId);
        // 부모 댓글 작성자에게 대댓글 알림 (본인 댓글은 제외)
        notificationService.notifyReply(parent, username);
        return response;
    }

    @Transactional
    public CommentResponse updateComment(Long postId, Long commentId, CommentRequest request, String username) {
        Comment comment = findComment(commentId, postId);
        checkOwnership(comment.getAuthor().getUsername(), username);
        comment.update(request.getContent());
        return new CommentResponse(comment);
    }

    @Transactional
    public void deleteComment(Long postId, Long commentId, String username) {
        Comment comment = findComment(commentId, postId);
        checkOwnership(comment.getAuthor().getUsername(), username);
        // 댓글 + 대댓글 수만큼 카운트 차감
        int count = 1 + comment.getReplies().size();
        commentRepository.delete(comment);
        for (int i = 0; i < count; i++) {
            postRepository.decrementCommentCount(postId);
        }
    }

    /**
     * 댓글 ID로 해당 댓글이 속한 게시물 ID를 반환한다.
     * 관리자 신고 패널에서 댓글 신고의 "보기" 링크가 올바른 게시물로 이동하도록 사용한다.
     *
     * @param commentId 댓글 ID
     * @return 해당 댓글이 속한 게시물 ID
     * @throws IllegalArgumentException 댓글을 찾을 수 없을 때
     */
    public Long getPostIdByCommentId(Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + commentId));
        return comment.getPost().getId();
    }

    /**
     * 관리자 전용 강제 삭제.
     * 소유자 검사 없이 댓글을 삭제한다.
     */
    @Transactional
    public void adminDeleteComment(Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + commentId));
        Long postId = comment.getPost().getId();
        int count = 1 + comment.getReplies().size();
        commentRepository.delete(comment);
        for (int i = 0; i < count; i++) {
            postRepository.decrementCommentCount(postId);
        }
    }

    private Comment findComment(Long commentId, Long postId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + commentId));
        if (!comment.getPost().getId().equals(postId)) {
            throw new IllegalArgumentException("Comment does not belong to the post");
        }
        return comment;
    }

    private Post findPost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
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
