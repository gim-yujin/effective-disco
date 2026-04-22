package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.NotificationPageState;
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
    private final NotificationSettingService notificationSettingService;

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
     * 댓글에 좋아요가 눌렸을 때 댓글 작성자에게 알림을 요청한다.
     * 좋아요 취소 시에는 호출되지 않는다.
     */
    public void notifyCommentLike(Comment comment, String likerUsername) {
        if (comment.getAuthor().getUsername().equals(likerUsername)) return;
        publishNotification(new NotificationRequestedEvent(
                comment.getAuthor().getUsername(),
                NotificationType.COMMENT_LIKE,
                likerUsername + "님이 회원님의 댓글을 좋아합니다.",
                "/posts/" + comment.getPost().getId() + "#comment-" + comment.getId()
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
     *
     * lock 제거:
     * 기존에는 수신자 User 행을 FOR UPDATE 로 잠가 store/read-all/read-page 를 직렬화했다.
     * 그러나 incrementUnreadNotificationCount 는 이미 atomic UPDATE (SET count = count + 1) 이고,
     * read 경로의 markPageAsReadByIds / markAllAsReadUpToId 도 WHERE isRead = false 덕분에
     * 실제 전환 수를 정확히 반환한다. FOR UPDATE 없이도 counter drift 가 발생하지 않으므로
     * lock convoy → pool saturation 병목을 제거한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void storeNotificationAfterCommit(NotificationRequestedEvent event) {
        UserRepository.NotificationRecipientSnapshot recipient = loadTestStepProfiler.profile(
                "notification.store.find-recipient",
                true,
                () -> userRepository.findNotificationRecipientSnapshotByUsername(event.recipientUsername()).orElse(null)
        );
        if (recipient == null) {
            return;
        }

        Notification notification = Notification.builder()
                .recipient(userRepository.getReferenceById(recipient.getId()))
                .type(event.type())
                .message(event.message())
                .link(event.link())
                .build();
        loadTestStepProfiler.profile(
                "notification.store.insert",
                true,
                () -> notificationRepository.save(notification)
        );
        loadTestStepProfiler.profile(
                "notification.store.counter.increment",
                true,
                () -> userRepository.incrementUnreadNotificationCount(recipient.getId())
        );
        scheduleUnreadCountRefreshAfterCommit(recipient.getUsername(), recipient.getUnreadNotificationCount() + 1);
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
     * 웹 알림 UI의 명시 액션은 recipient 전체 unread row 를 한 번에 읽음 처리하지 않는다.
     * 사용자가 실제로 보고 있는 현재 페이지 batch 만 읽음 처리하면
     * read-all bulk update 로 인한 장시간 drift 를 크게 줄일 수 있다.
     */
    @Transactional
    public int markPageAsRead(String username, int page, int size) {
        UserRepository.NotificationRecipientSnapshot recipient = findNotificationRecipient(username);
        return loadTestStepProfiler.profile(
                "notification.read-page.web.transition",
                true,
                () -> markNotificationPageRead(recipient, page, size)
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
     * 문제 해결:
     * baseline load test 는 사용자가 현재 보고 있는 알림 batch 만 읽는 현실적인 경로를 따로 측정해야 한다.
     * stress 용 read-all 과 분리된 read-page 요약 경로를 두어
     * 정상 baseline 과 worst-case read-all stress 를 같은 메트릭으로 섞지 않게 한다.
     */
    @Transactional
    public NotificationReadSummary markPageAsReadForLoadTest(String username, int page, int size) {
        UserRepository.NotificationRecipientSnapshot recipient = findNotificationRecipient(username);
        long transitionedCount = loadTestStepProfiler.profile(
                "notification.read-page.summary.transition",
                true,
                () -> markNotificationPageRead(recipient, page, size)
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

    /**
     * 수신자의 알림 설정을 확인한 뒤 이벤트를 발행한다.
     * 해당 알림 타입이 비활성(off)이면 이벤트를 발행하지 않는다.
     */
    private void publishNotification(NotificationRequestedEvent event) {
        if (!notificationSettingService.isEnabled(event.recipientUsername(), event.type())) {
            return;
        }
        eventPublisher.publishEvent(event);
    }

    /**
     * 문제 해결:
     * 알림 저장 트랜잭션 안에서 바로 SSE를 보내면 커밋 실패 시 잘못된 숫자가 노출될 수 있다.
     * 다만 afterCommit 시점에 unread count를 다시 SELECT 하면 notification.store hot path 가
     * 불필요하게 한 번 더 DB를 친다. 트랜잭션 안에서 확정한 unread 값을 그대로 넘겨
     * UI와 DB 시점을 맞추면서 post-commit 재조회도 없앤다.
     */
    private void scheduleUnreadCountRefreshAfterCommit(String username, long unreadCount) {
        Runnable push = () -> sseEmitterService.sendCount(username, unreadCount);

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

    private void scheduleUnreadCountRefreshAfterCommit(String username) {
        scheduleUnreadCountRefreshAfterCommit(username, getUnreadCount(username));
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
        return PaginationUtils.clampPageSize(size, 50);
    }

    /**
     * lock 제거:
     * 기존에는 findByIdForUpdate 로 User 행을 잠근 뒤 page-state 조회 → mark → refresh 를 직렬화했다.
     * markPageAsReadByIds 는 WHERE isRead = false 조건이 있어 실제 전환 수를 정확히 반환하므로
     * 그 값을 atomic decrement delta 로 쓰면 lock 없이도 counter drift 가 발생하지 않는다.
     * refreshUnreadNotificationCount (COUNT subquery) 도 decrementUnreadNotificationCount 로 교체해
     * read-page hot path 의 connection 점유 시간을 줄인다.
     */
    private int markNotificationPageRead(UserRepository.NotificationRecipientSnapshot recipient, int page, int size) {
        Long recipientId = recipient.getId();
        PageRequest pageable = PageRequest.of(Math.max(page, 0), normalizeNotificationPageSize(size));
        Slice<NotificationPageState> slice = loadTestStepProfiler.profile(
                "notification.read-page.page-state",
                true,
                () -> notificationRepository.findPageStateSliceByRecipientIdOrderByCreatedAtDesc(recipientId, pageable)
        );
        List<Long> unreadIds = slice.getContent().stream()
                .filter(notification -> !notification.read())
                .map(NotificationPageState::id)
                .toList();

        if (unreadIds.isEmpty()) {
            return 0;
        }

        int transitionedCount = loadTestStepProfiler.profile(
                "notification.read-page.mark-ids",
                true,
                () -> notificationRepository.markPageAsReadByIds(recipientId, unreadIds)
        );
        if (transitionedCount > 0) {
            loadTestStepProfiler.profile(
                    "notification.read-page.counter.decrement",
                    true,
                    () -> userRepository.decrementUnreadNotificationCount(recipientId, transitionedCount)
            );
        }
        scheduleUnreadCountRefreshAfterCommit(recipient.getUsername());
        return transitionedCount;
    }

    /**
     * lock 제거:
     * 기존에는 findByIdForUpdate 로 User 행을 잠근 뒤 cutoff 조회 → mark → refresh 를 직렬화했다.
     * markAllAsReadUpToId 는 WHERE isRead = false AND id <= cutoffId 조건이 있어
     * 실제 전환 수를 정확히 반환하므로 atomic decrement 로 counter 를 맞출 수 있다.
     * cutoffId 덕분에 mark 이후 새로 삽입된 알림은 전환 대상에서 제외되어
     * store 의 increment 와 겹치지 않는다.
     */
    private long markAllUnreadNotificationsRead(UserRepository.NotificationRecipientSnapshot recipient) {
        Long recipientId = recipient.getId();
        Long cutoffId = loadTestStepProfiler.profile(
                "notification.read-all.find-cutoff",
                true,
                () -> notificationRepository.findLatestNotificationIdByRecipientId(recipientId)
        );
        if (cutoffId == null) {
            return 0L;
        }
        int transitionedCount = loadTestStepProfiler.profile(
                "notification.read-all.mark-cutoff",
                true,
                () -> notificationRepository.markAllAsReadUpToId(recipientId, cutoffId)
        );
        if (transitionedCount > 0) {
            loadTestStepProfiler.profile(
                    "notification.read-all.counter.decrement",
                    true,
                    () -> userRepository.decrementUnreadNotificationCount(recipientId, transitionedCount)
            );
        }
        scheduleUnreadCountRefreshAfterCommit(recipient.getUsername());
        return transitionedCount;
    }

    public record NotificationReadSummary(int listedNotificationCount, long unreadCount) {}
}
