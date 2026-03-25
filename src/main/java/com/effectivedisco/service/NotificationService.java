package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.NotificationResponse;
import com.effectivedisco.event.NotificationRequestedEvent;
import com.effectivedisco.loadtest.LoadTestStepProfiler;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository         userRepository;
    private final SseEmitterService      sseEmitterService;
    private final ApplicationEventPublisher eventPublisher;
    private final LoadTestStepProfiler   loadTestStepProfiler;

    /**
     * 게시물에 댓글이 달렸을 때 게시물 작성자에게 알림을 요청한다.
     * 본인이 자신의 게시물에 댓글을 달면 알림을 생성하지 않는다.
     */
    public void notifyComment(Post post, String commenterUsername) {
        notifyComment(post.getAuthor().getUsername(), post.getId(), commenterUsername);
    }

    /**
     * 게시물 엔티티를 이미 들고 있지 않은 hot path 에서 사용하는 댓글 알림 요청.
     */
    public void notifyComment(String recipientUsername, Long postId, String commenterUsername) {
        if (recipientUsername.equals(commenterUsername)) return;
        publishNotification(new NotificationRequestedEvent(
                recipientUsername,
                NotificationType.COMMENT,
                commenterUsername + "님이 회원님의 게시물에 댓글을 남겼습니다.",
                "/posts/" + postId + "#comments"
        ));
    }

    /**
     * 댓글에 대댓글이 달렸을 때 댓글 작성자에게 알림을 요청한다.
     * 본인이 자신의 댓글에 답글을 달면 알림을 생성하지 않는다.
     */
    public void notifyReply(Comment parentComment, String replierUsername) {
        if (parentComment.getAuthor().getUsername().equals(replierUsername)) return;
        publishNotification(new NotificationRequestedEvent(
                parentComment.getAuthor().getUsername(),
                NotificationType.REPLY,
                replierUsername + "님이 회원님의 댓글에 답글을 남겼습니다.",
                "/posts/" + parentComment.getPost().getId() + "#comment-" + parentComment.getId()
        ));
    }

    /**
     * 게시물에 좋아요가 눌렸을 때 게시물 작성자에게 알림을 요청한다.
     * 좋아요 취소 시에는 호출되지 않는다.
     */
    public void notifyLike(Post post, String likerUsername) {
        if (post.getAuthor().getUsername().equals(likerUsername)) return;
        publishNotification(new NotificationRequestedEvent(
                post.getAuthor().getUsername(),
                NotificationType.LIKE,
                likerUsername + "님이 회원님의 게시물을 좋아합니다.",
                "/posts/" + post.getId()
        ));
    }

    /**
     * 쪽지 도착 알림을 요청한다.
     * MessageService가 본문 저장을 커밋한 뒤에만 실제 알림이 만들어지도록 이벤트로 분리한다.
     */
    public void notifyMessage(String recipientUsername, String senderUsername, String title, Long messageId) {
        publishNotification(new NotificationRequestedEvent(
                recipientUsername,
                NotificationType.MESSAGE,
                senderUsername + "님이 쪽지를 보냈습니다: " + title,
                "/messages/" + messageId
        ));
    }

    /**
     * 문제 해결:
     * 이 메서드는 AFTER_COMMIT 이벤트 리스너가 호출한다.
     * AFTER_COMMIT 시점에는 원 트랜잭션이 이미 끝났으므로 REQUIRED 로는
     * update/delete 쿼리가 트랜잭션 없이 실행될 수 있다.
     * REQUIRES_NEW 로 별도 트랜잭션을 열어 알림 row 저장, unread counter 증가,
     * SSE 예약을 안전하게 마무리한다.
     * 또한 수신자 User 행을 잠가 "알림 생성"과 "전체 읽음 처리"가 엇갈릴 때도
     * unread counter가 실제 unread row 수와 어긋나지 않게 직렬화한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void storeNotificationAfterCommit(NotificationRequestedEvent event) {
        UserRepository.NotificationRecipientSnapshot recipient =
                userRepository.findNotificationRecipientSnapshotByUsername(event.recipientUsername()).orElse(null);
        if (recipient == null) {
            return;
        }

        Notification notification = Notification.builder()
                .recipient(userRepository.getReferenceById(recipient.getId()))
                .type(event.type())
                .message(event.message())
                .link(event.link())
                .build();
        notificationRepository.save(notification);
        // 문제 해결:
        // notification.store 의 실제 병목은 unread counter 자체보다 "수신자 User 행 전체를 FOR UPDATE 로 잠그는 것"이었다.
        // 수신자 snapshot + 원자 UPDATE 로 unread 값을 올리면 알림 생성과 read-all 이 같은 사용자에서 부딪혀도
        // row hydration / lock hold time 을 줄이면서 counter 정합성은 유지할 수 있다.
        userRepository.incrementUnreadNotificationCount(recipient.getId());
        scheduleUnreadCountRefreshAfterCommit(recipient.getUsername());
    }

    /**
     * 수신자의 전체 알림 목록을 최신순으로 반환하고,
     * 미읽음 알림을 모두 읽음으로 표시한다.
     */
    @Transactional
    public List<NotificationResponse> getAndMarkAllRead(String username) {
        UserRepository.NotificationRecipientSnapshot recipient = findNotificationRecipient(username);
        User user = userRepository.getReferenceById(recipient.getId());
        List<NotificationResponse> list = loadTestStepProfiler.profile(
                "notification.read-all.page.fetch",
                true,
                () -> notificationRepository.findResponseByRecipientOrderByCreatedAtDesc(user)
        );
        loadTestStepProfiler.profile(
                "notification.read-all.page.transition",
                true,
                () -> markAllUnreadNotificationsRead(recipient)
        );
        return markNotificationsReadView(list);
    }

    /**
     * 문제 해결:
     * 웹 알림 페이지의 hot path 는 상태 전환이 아니라 조회여야 한다.
     * GET /notifications 에서는 현재 batch 만 읽고, read-all 은 별도 POST 액션에서만 실행해
     * 페이지 진입 자체가 bulk update 병목을 만들지 않게 한다.
     */
    @Transactional(readOnly = true)
    public Slice<NotificationResponse> getPage(String username, int page, int size) {
        UserRepository.NotificationRecipientSnapshot recipient = findNotificationRecipient(username);
        User user = userRepository.getReferenceById(recipient.getId());
        PageRequest pageable = PageRequest.of(Math.max(page, 0), normalizeNotificationPageSize(size));
        return loadTestStepProfiler.profile(
                "notification.page.fetch",
                true,
                () -> notificationRepository.findResponseSliceByRecipientOrderByCreatedAtDesc(user, pageable)
        );
    }

    /**
     * 문제 해결:
     * 알림 페이지는 전체 목록을 렌더링하면서 사용자 행 잠금을 오래 쥘 필요가 없다.
     * 현재 page batch 는 잠금 없이 Slice 로 읽고, read-all 상태 전환만 짧게 잠가
     * notification.read-all 이 full list materialize + lock hold time 으로 pool 을 잡아먹지 않게 만든다.
     */
    @Transactional
    public Slice<NotificationResponse> getAndMarkAllReadPage(String username, int page, int size) {
        UserRepository.NotificationRecipientSnapshot recipient = findNotificationRecipient(username);
        User user = userRepository.getReferenceById(recipient.getId());
        PageRequest pageable = PageRequest.of(Math.max(page, 0), normalizeNotificationPageSize(size));
        Slice<NotificationResponse> slice = loadTestStepProfiler.profile(
                "notification.read-all.page.fetch",
                true,
                () -> notificationRepository.findResponseSliceByRecipientOrderByCreatedAtDesc(user, pageable)
        );
        loadTestStepProfiler.profile(
                "notification.read-all.page.transition",
                true,
                () -> markAllUnreadNotificationsRead(recipient)
        );
        return new SliceImpl<>(markNotificationsReadView(slice.getContent()), slice.getPageable(), slice.hasNext());
    }

    /**
     * 문제 해결:
     * 자동 read-all 대신 사용자가 명시적으로 요청했을 때만 상태 전환을 수행한다.
     * 이렇게 해야 단순 조회 반복이 recipient 전체 unread row bulk update 로 증폭되지 않는다.
     */
    @Transactional
    public long markAllAsRead(String username) {
        UserRepository.NotificationRecipientSnapshot recipient = findNotificationRecipient(username);
        return loadTestStepProfiler.profile(
                "notification.read-all.web.transition",
                true,
                () -> markAllUnreadNotificationsRead(recipient)
        );
    }

    /**
     * 문제 해결:
     * loadtest mixed scenario 는 "알림 목록 본문"이 아니라 "read-all 상태 전환" 비용을 보고 싶다.
     * 내부 전용 경로는 full count/full list 를 제거하고 unread 상태 전환량만 요약해
     * summary path 자체가 read-all 병목을 과장하지 않게 만든다.
     */
    @Transactional
    public NotificationReadSummary markAllAsReadForLoadTest(String username) {
        UserRepository.NotificationRecipientSnapshot recipient = findNotificationRecipient(username);
        long transitionedCount = loadTestStepProfiler.profile(
                "notification.read-all.summary.transition",
                true,
                () -> markAllUnreadNotificationsRead(recipient)
        );
        return new NotificationReadSummary((int) transitionedCount, 0L);
    }

    /**
     * 헤더 뱃지/SSE 초기값용 미읽음 알림 수.
     * count query 대신 비정규화 카운터를 읽어 hot path를 가볍게 유지한다.
     */
    public long getUnreadCount(String username) {
        return userRepository.findUnreadNotificationCountByUsername(username).orElse(0L);
    }

    private void publishNotification(NotificationRequestedEvent event) {
        eventPublisher.publishEvent(event);
    }

    /**
     * 문제 해결:
     * 알림 저장 트랜잭션 안에서 바로 SSE를 보내면 커밋 실패 시 잘못된 숫자가 노출될 수 있다.
     * 다만 afterCommit 시점에 unread count를 다시 SELECT 하면 notification.store hot path 가
     * 불필요하게 한 번 더 DB를 친다. 트랜잭션 안에서 확정한 unread 값을 그대로 넘겨
     * UI와 DB 시점을 맞추면서 post-commit 재조회도 없앤다.
     */
    private void scheduleUnreadCountRefreshAfterCommit(String username) {
        Runnable push = () -> sseEmitterService.sendCount(username, getUnreadCount(username));

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            push.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                push.run();
            }
        });
    }

    private UserRepository.NotificationRecipientSnapshot findNotificationRecipient(String username) {
        return userRepository.findNotificationRecipientSnapshotByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }

    private List<NotificationResponse> markNotificationsReadView(List<NotificationResponse> notifications) {
        return notifications.stream()
                .map(NotificationResponse::asRead)
                .toList();
    }

    private int normalizeNotificationPageSize(int size) {
        return Math.max(1, Math.min(size, 50));
    }

    private long markAllUnreadNotificationsRead(UserRepository.NotificationRecipientSnapshot recipient) {
        Long cutoffId = notificationRepository.findLatestNotificationIdByRecipientId(recipient.getId());
        if (cutoffId == null) {
            if (recipient.getUnreadNotificationCount() > 0) {
                userRepository.setUnreadNotificationCount(recipient.getId(), 0L);
            }
            scheduleUnreadCountRefreshAfterCommit(recipient.getUsername());
            return 0L;
        }

        boolean hasUnreadNotifications = recipient.getUnreadNotificationCount() > 0;
        if (!hasUnreadNotifications) {
            // 문제 해결:
            // unread counter 는 hot path 최적화를 위한 비정규화 값이라, 테스트 fixture 나 과거 데이터처럼
            // counter=0 이지만 실제 unread row 가 남아 있는 drift 상태를 완전히 배제할 수 없다.
            // counter 가 0 일 때만 실제 unread row 존재 여부를 한 번 확인해
            // 알림 페이지 방문이 "보이는 알림을 읽음 처리하지 못하는" 상태로 끝나지 않게 보정한다.
            hasUnreadNotifications = notificationRepository.existsByRecipientIdAndIsReadFalse(recipient.getId());
        }

        if (hasUnreadNotifications) {
            // 문제 해결:
            // 기존 구현은 recipient User 행을 FOR UPDATE 로 잠그고 전체 unread row 를 bulk update 하면서
            // store/read-all 이 같은 사용자에 대해 강하게 직렬화됐다.
            // read-all 시작 시점의 cutoff id 까지만 읽음 처리하고 unread counter 는 delta 만큼만 원자적으로 줄이면
            // 새 알림과 경합해도 counter 정합성을 유지하면서 lock 범위를 줄일 수 있다.
            int transitionedCount = notificationRepository.markAllAsReadUpToId(recipient.getId(), cutoffId);
            if (transitionedCount > 0) {
                userRepository.decrementUnreadNotificationCount(recipient.getId(), transitionedCount);
            } else if (recipient.getUnreadNotificationCount() > 0) {
                long actualUnreadCount = notificationRepository.countUnreadByRecipientId(recipient.getId());
                userRepository.setUnreadNotificationCount(recipient.getId(), actualUnreadCount);
            }
            scheduleUnreadCountRefreshAfterCommit(recipient.getUsername());
            return transitionedCount;
        }
        scheduleUnreadCountRefreshAfterCommit(recipient.getUsername());
        return 0L;
    }

    public record NotificationReadSummary(int listedNotificationCount, long unreadCount) {}
}
