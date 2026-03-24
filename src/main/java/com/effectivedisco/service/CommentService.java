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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository   commentRepository;
    private final PostRepository      postRepository;
    private final UserRepository      userRepository;
    private final NotificationService notificationService;
    private final EntityManager       entityManager;

    @Transactional(readOnly = true)
    public Page<CommentResponse> getCommentsPage(Long postId, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.max(1, Math.min(size, 100));
        PageRequest pageable = PageRequest.of(pageNumber, pageSize);

        Page<CommentRepository.CommentViewRow> topLevelComments =
                commentRepository.findTopLevelCommentRowsByPostIdOrderByCreatedAtAsc(postId, pageable);

        List<Long> parentIds = topLevelComments.getContent().stream()
                .map(CommentRepository.CommentViewRow::getId)
                .toList();

        Map<Long, List<CommentResponse>> repliesByParentId = new LinkedHashMap<>();
        if (!parentIds.isEmpty()) {
            for (CommentRepository.CommentViewRow replyRow :
                    commentRepository.findReplyRowsByParentIdInOrderByCreatedAtAsc(parentIds)) {
                repliesByParentId.computeIfAbsent(replyRow.getParentId(), ignored -> new ArrayList<>())
                        .add(toCommentResponse(replyRow, List.of()));
            }
        }

        List<CommentResponse> content = topLevelComments.getContent().stream()
                .map(comment -> toCommentResponse(
                        comment,
                        repliesByParentId.getOrDefault(comment.getId(), List.of())
                ))
                .toList();

        return new PageImpl<>(content, pageable, topLevelComments.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(Long postId) {
        return getCommentsPage(postId, 0, 50).getContent();
    }

    @Transactional(readOnly = true)
    public int getLastTopLevelCommentPage(Long postId, int size) {
        int pageSize = Math.max(1, Math.min(size, 100));
        long topLevelCount = commentRepository.countByPostIdAndParentIsNull(postId);
        if (topLevelCount == 0) {
            return 0;
        }
        return (int) ((topLevelCount - 1) / pageSize);
    }

    @Transactional(readOnly = true)
    public int getCommentPageForAnchor(Long postId, Long commentId, int size) {
        int pageSize = Math.max(1, Math.min(size, 100));
        Long topLevelCommentId = commentRepository.findTopLevelCommentIdByCommentId(commentId);
        if (topLevelCommentId == null) {
            throw new IllegalArgumentException("Comment not found: " + commentId);
        }
        CommentRepository.TopLevelCommentCursor cursor =
                commentRepository.findTopLevelCommentCursorById(topLevelCommentId);
        if (cursor == null) {
            throw new IllegalArgumentException("Comment not found: " + commentId);
        }
        long rank = commentRepository.countTopLevelCommentsBeforeOrAt(postId, cursor.getCreatedAt(), cursor.getId());
        if (rank == 0) {
            return 0;
        }
        return (int) ((rank - 1) / pageSize);
    }

    @Transactional
    public CommentResponse createComment(Long postId, CommentRequest request, String username) {
        PostRepository.CommentNotificationTarget postTarget = findCommentNotificationTarget(postId);
        UserRepository.CommentAuthorSnapshot authorSnapshot = findCommentAuthorSnapshot(username);
        // 문제 해결:
        // comment.create 는 댓글 1건 저장을 위해 Post/User 엔티티 전체를 읽고,
        // 댓글 알림에서 post.author LAZY select 를 추가로 타며 SQL 이 불어났다.
        // 존재 확인과 응답/알림에 필요한 최소 컬럼만 projection 으로 읽고 연관은 getReference 로 묶어
        // hot path SQL 을 "게시물 확인 + 작성자 확인 + INSERT + 카운터 UPDATE" 수준으로 고정한다.
        Comment comment = Comment.builder()
                .content(request.getContent())
                .post(entityManager.getReference(Post.class, postTarget.getId()))
                .author(entityManager.getReference(User.class, authorSnapshot.getId()))
                .build();
        Comment savedComment = commentRepository.save(comment);
        postRepository.incrementCommentCount(postId);
        // 게시물 작성자에게 댓글 알림 (본인 댓글은 제외)
        notificationService.notifyComment(postTarget.getAuthorUsername(), postId, username);
        return new CommentResponse(savedComment, authorSnapshot.getUsername(), authorSnapshot.getProfileImageUrl());
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
        CommentResponse response = new CommentResponse(
                commentRepository.save(reply),
                user.getUsername(),
                user.getProfileImageUrl()
        );
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

    private PostRepository.CommentNotificationTarget findCommentNotificationTarget(Long postId) {
        return postRepository.findCommentNotificationTargetById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private UserRepository.CommentAuthorSnapshot findCommentAuthorSnapshot(String username) {
        return userRepository.findCommentAuthorSnapshotByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private void checkOwnership(String ownerUsername, String requestUsername) {
        if (!ownerUsername.equals(requestUsername)) {
            throw new AccessDeniedException("No permission to modify this resource");
        }
    }

    private CommentResponse toCommentResponse(CommentRepository.CommentViewRow comment,
                                              List<CommentResponse> replies) {
        return new CommentResponse(
                comment.getId(),
                comment.getContent(),
                comment.getAuthorUsername(),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                comment.getAuthorProfileImageUrl(),
                replies
        );
    }
}
