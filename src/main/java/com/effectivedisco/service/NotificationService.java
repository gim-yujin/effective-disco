package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.NotificationResponse;
import com.effectivedisco.event.NotificationRequestedEvent;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository         userRepository;
    private final SseEmitterService      sseEmitterService;
    private final ApplicationEventPublisher eventPublisher;

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
        User recipient = userRepository.findByUsernameForUpdate(event.recipientUsername()).orElse(null);
        if (recipient == null) {
            return;
        }

        Notification notification = Notification.builder()
                .recipient(recipient)
                .type(event.type())
                .message(event.message())
                .link(event.link())
                .build();
        notificationRepository.save(notification);
        // 문제 해결:
        // notification.store 는 알림 row 를 INSERT 한 뒤 unread count 를 다시 SELECT/COUNT 하면서
        // DB를 한 번 더 돌고 있었다. 잠금으로 직렬화된 수신자 엔티티에서 최종 unread 값을 바로 올리고
        // 그 값을 after-commit push 에 넘기면 저장 경로를 최소 SQL 로 유지할 수 있다.
        recipient.incrementUnreadNotificationCount();
        scheduleUnreadCountPushAfterCommit(recipient.getUsername(), recipient.getUnreadNotificationCount());
    }

    /**
     * 수신자의 전체 알림 목록을 최신순으로 반환하고,
     * 미읽음 알림을 모두 읽음으로 표시한다.
     */
    @Transactional
    public List<NotificationResponse> getAndMarkAllRead(String username) {
        User user = findUserForUpdate(username);
        List<NotificationResponse> list = notificationRepository
                .findByRecipientOrderByCreatedAtDesc(user)
                .stream()
                .map(NotificationResponse::new)
                .collect(Collectors.toList());

        if (user.getUnreadNotificationCount() > 0) {
            // 문제 해결:
            // 이미 unread 가 0 인 사용자는 bulk UPDATE 를 다시 날릴 이유가 없다.
            // 직렬화된 User 엔티티의 비정규화 카운터를 기준으로 필요한 경우에만 읽음 처리를 수행한다.
            notificationRepository.markAllAsRead(user);
            user.resetUnreadNotificationCount();
        }
        scheduleUnreadCountPushAfterCommit(username, user.getUnreadNotificationCount());
        return list;
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
    private void scheduleUnreadCountPushAfterCommit(String username, long unreadCount) {
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

    private User findUserForUpdate(String username) {
        return userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }
}
