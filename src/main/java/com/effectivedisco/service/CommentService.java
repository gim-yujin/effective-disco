package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.CommentRequest;
import com.effectivedisco.dto.response.CommentResponse;
import com.effectivedisco.dto.response.LikeResponse;
import com.effectivedisco.loadtest.LoadTestStepProfiler;
import com.effectivedisco.repository.CommentLikeRepository;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.RelationAtomicInserter;
import com.effectivedisco.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CommentService {

    /** 비로그인/익명 뷰어 id sentinel — EXISTS 서브쿼리가 절대 매칭되지 않도록 음수 값을 사용한다. */
    private static final Long ANONYMOUS_VIEWER_ID = -1L;

    private final CommentRepository       commentRepository;
    private final CommentLikeRepository   commentLikeRepository;
    private final PostRepository          postRepository;
    private final UserRepository          userRepository;
    private final NotificationService     notificationService;
    private final EntityManager           entityManager;
    private final UserLookupService       userLookupService;
    private final LoadTestStepProfiler    loadTestStepProfiler;
    private final RelationAtomicInserter  relationAtomicInserter;

    /** 댓글 최대 깊이. 0 = 최상위만, 1 = 1단계 대댓글, 2 = 2단계까지 허용 (기본값 2) */
    private final int maxDepth;

    public CommentService(CommentRepository commentRepository,
                          CommentLikeRepository commentLikeRepository,
                          PostRepository postRepository,
                          UserRepository userRepository,
                          NotificationService notificationService,
                          EntityManager entityManager,
                          UserLookupService userLookupService,
                          LoadTestStepProfiler loadTestStepProfiler,
                          RelationAtomicInserter relationAtomicInserter,
                          @Value("${app.comment.max-depth:2}") int maxDepth) {
        this.commentRepository      = commentRepository;
        this.commentLikeRepository  = commentLikeRepository;
        this.postRepository         = postRepository;
        this.userRepository         = userRepository;
        this.notificationService    = notificationService;
        this.entityManager          = entityManager;
        this.userLookupService      = userLookupService;
        this.loadTestStepProfiler   = loadTestStepProfiler;
        this.relationAtomicInserter = relationAtomicInserter;
        this.maxDepth               = maxDepth;
    }

    @Transactional(readOnly = true)
    public Page<CommentResponse> getCommentsPage(Long postId, int page, int size, String viewerUsername) {
        int pageNumber = Math.max(page, 0);
        int pageSize = PaginationUtils.clampPageSize(size, 100);
        PageRequest pageable = PageRequest.of(pageNumber, pageSize);

        Long viewerUserId = resolveViewerUserId(viewerUsername);

        Page<CommentRepository.CommentViewRow> topLevelComments =
                commentRepository.findTopLevelCommentRowsByPostIdOrderByCreatedAtAsc(postId, viewerUserId, pageable);

        List<Long> parentIds = topLevelComments.getContent().stream()
                .map(CommentRepository.CommentViewRow::getId)
                .toList();

        Map<Long, List<CommentResponse>> repliesByParentId = new LinkedHashMap<>();
        if (!parentIds.isEmpty()) {
            for (CommentRepository.CommentViewRow replyRow :
                    commentRepository.findReplyRowsByParentIdInOrderByCreatedAtAsc(parentIds, viewerUserId)) {
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

    /** 하위 호환: viewer 미지정 경로는 익명으로 취급 (likedByMe 항상 false). */
    @Transactional(readOnly = true)
    public Page<CommentResponse> getCommentsPage(Long postId, int page, int size) {
        return getCommentsPage(postId, page, size, null);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(Long postId, String viewerUsername) {
        return getCommentsPage(postId, 0, 50, viewerUsername).getContent();
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(Long postId) {
        return getComments(postId, null);
    }

    @Transactional(readOnly = true)
    public int getLastTopLevelCommentPage(Long postId, int size) {
        int pageSize = PaginationUtils.clampPageSize(size, 100);
        long topLevelCount = commentRepository.countByPostIdAndParentIsNull(postId);
        if (topLevelCount == 0) {
            return 0;
        }
        return (int) ((topLevelCount - 1) / pageSize);
    }

    @Transactional(readOnly = true)
    public int getCommentPageForAnchor(Long postId, Long commentId, int size) {
        int pageSize = PaginationUtils.clampPageSize(size, 100);
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
        User user = userLookupService.findByUsername(username);
        Comment parent = commentRepository.findById(parentCommentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + parentCommentId));
        if (!parent.getPost().getId().equals(postId)) {
            throw new IllegalArgumentException("Comment does not belong to the post");
        }
        // 부모 댓글의 깊이 + 1 이 최대 허용 깊이를 초과하면 답글을 달 수 없다
        int replyDepth = parent.getDepth() + 1;
        if (replyDepth > maxDepth) {
            throw new IllegalArgumentException(
                    "최대 댓글 깊이(" + maxDepth + "단계)를 초과하여 답글을 달 수 없습니다");
        }
        Comment reply = Comment.builder()
                .content(request.getContent())
                .post(post)
                .author(user)
                .parent(parent)
                .depth(replyDepth)
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

    /* ── 댓글 좋아요 ───────────────────────────────────────── */

    /**
     * 댓글에 좋아요를 건다.
     * 이미 좋아요 상태이면 그대로 성공 처리한다 (idempotent).
     *
     * PostService.likePost 와 동일한 패턴:
     * unique (comment_id, user_id) 제약 + atomic upsert (PG: ON CONFLICT DO NOTHING,
     * H2: INSERT ... WHERE NOT EXISTS) 로 no-op 경로에서 lock 없이 상수-시간으로 완료된다.
     */
    @Transactional
    public LikeResponse likeComment(Long commentId, String username) {
        return loadTestStepProfiler.profile(
                "comment.like.add",
                true,
                () -> {
                    User user = loadTestStepProfiler.profile(
                            "comment.like.add.resolve-user", true,
                            () -> userLookupService.findByUsername(username));
                    Comment comment = loadTestStepProfiler.profile(
                            "comment.like.add.resolve-comment", true,
                            () -> findCommentById(commentId));

                    int inserted = loadTestStepProfiler.profile(
                            "comment.like.add.insert", true,
                            () -> relationAtomicInserter.insertCommentLike(
                                    comment.getId(), user.getId(), LocalDateTime.now()));
                    if (inserted > 0) {
                        loadTestStepProfiler.profile(
                                "comment.like.add.counter-increment", true,
                                () -> commentRepository.incrementLikeCount(commentId));
                        loadTestStepProfiler.profile(
                                "comment.like.add.notify", true,
                                () -> notificationService.notifyCommentLike(comment, username));
                    }
                    return loadTestStepProfiler.profile(
                            "comment.like.add.response", true,
                            () -> buildLikeResponse(commentId, true));
                }
        );
    }

    /**
     * 댓글 좋아요를 해제한다.
     * 이미 해제된 상태이면 그대로 성공 처리한다 (idempotent).
     *
     * PostService.unlikePost 와 동일한 패턴: bulk DELETE 의 반환 row 수로 counter 감소를 gating 한다.
     */
    @Transactional
    public LikeResponse unlikeComment(Long commentId, String username) {
        return loadTestStepProfiler.profile(
                "comment.like.remove",
                true,
                () -> {
                    User user = loadTestStepProfiler.profile(
                            "comment.like.remove.resolve-user", true,
                            () -> userLookupService.findByUsername(username));
                    Comment comment = loadTestStepProfiler.profile(
                            "comment.like.remove.resolve-comment", true,
                            () -> findCommentById(commentId));

                    long deleted = loadTestStepProfiler.profile(
                            "comment.like.remove.delete", true,
                            () -> commentLikeRepository.deleteByCommentAndUser(comment, user));
                    if (deleted > 0) {
                        loadTestStepProfiler.profile(
                                "comment.like.remove.counter-decrement", true,
                                () -> commentRepository.decrementLikeCount(commentId));
                    }
                    return loadTestStepProfiler.profile(
                            "comment.like.remove.response", true,
                            () -> buildLikeResponse(commentId, false));
                }
        );
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

    private Comment findCommentById(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("Comment not found: " + commentId));
    }

    private Post findPost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
    }

    private PostRepository.CommentNotificationTarget findCommentNotificationTarget(Long postId) {
        return postRepository.findCommentNotificationTargetById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
    }

    private UserRepository.CommentAuthorSnapshot findCommentAuthorSnapshot(String username) {
        return userRepository.findCommentAuthorSnapshotByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private void checkOwnership(String ownerUsername, String requestUsername) {
        OwnershipChecker.check(ownerUsername, requestUsername);
    }

    /** viewer username → user id (비로그인/미존재 시 sentinel -1L). */
    private Long resolveViewerUserId(String viewerUsername) {
        if (viewerUsername == null || viewerUsername.isBlank()) {
            return ANONYMOUS_VIEWER_ID;
        }
        return userRepository.findIdByUsername(viewerUsername).orElse(ANONYMOUS_VIEWER_ID);
    }

    private LikeResponse buildLikeResponse(Long commentId, boolean liked) {
        return new LikeResponse(liked, commentRepository.findLikeCountById(commentId));
    }

    private CommentResponse toCommentResponse(CommentRepository.CommentViewRow comment,
                                              List<CommentResponse> replies) {
        return new CommentResponse(
                comment.getId(),
                comment.getContent(),
                comment.getAuthorUsername(),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                comment.getDepth(),
                comment.getAuthorProfileImageUrl(),
                comment.getLikeCount(),
                comment.getLikedByMe(),
                replies
        );
    }
}
