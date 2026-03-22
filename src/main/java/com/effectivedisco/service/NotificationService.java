package com.effectivedisco.service;

import com.effectivedisco.domain.*;
import com.effectivedisco.dto.response.NotificationResponse;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository         userRepository;

    /**
     * SseEmitterService 를 @Lazy로 주입한다.
     *
     * NotificationService ↔ SseEmitterService 가 서로를 직접 참조하면
     * Spring이 순환 의존성을 감지해 오류를 낸다.
     * @Lazy를 사용하면 SseEmitterService 빈이 실제로 처음 호출될 때까지
     * 프록시 객체만 주입되므로 초기화 시점의 순환 의존 문제를 피할 수 있다.
     */
    @Lazy
    @Autowired
    private SseEmitterService sseEmitterService;

    /**
     * 게시물에 댓글이 달렸을 때 게시물 작성자에게 알림을 생성한다.
     * 본인이 자신의 게시물에 댓글을 달면 알림을 생성하지 않는다.
     */
    @Transactional
    public void notifyComment(Post post, String commenterUsername) {
        if (post.getAuthor().getUsername().equals(commenterUsername)) return;
        Notification n = Notification.builder()
                .recipient(post.getAuthor())
                .type(NotificationType.COMMENT)
                .message(commenterUsername + "님이 회원님의 게시물에 댓글을 남겼습니다.")
                .link("/posts/" + post.getId() + "#comments")
                .build();
        notificationRepository.save(n);
        // 알림 저장 후 SSE로 실시간 미읽음 수 push
        pushUnreadCount(post.getAuthor().getUsername());
    }

    /**
     * 댓글에 대댓글이 달렸을 때 댓글 작성자에게 알림을 생성한다.
     * 본인이 자신의 댓글에 답글을 달면 알림을 생성하지 않는다.
     */
    @Transactional
    public void notifyReply(Comment parentComment, String replierUsername) {
        if (parentComment.getAuthor().getUsername().equals(replierUsername)) return;
        Notification n = Notification.builder()
                .recipient(parentComment.getAuthor())
                .type(NotificationType.REPLY)
                .message(replierUsername + "님이 회원님의 댓글에 답글을 남겼습니다.")
                .link("/posts/" + parentComment.getPost().getId() + "#comment-" + parentComment.getId())
                .build();
        notificationRepository.save(n);
        pushUnreadCount(parentComment.getAuthor().getUsername());
    }

    /**
     * 게시물에 좋아요가 눌렸을 때 게시물 작성자에게 알림을 생성한다.
     * 좋아요 취소 시에는 알림을 생성하지 않는다.
     * 본인 게시물에 좋아요를 누르면 알림을 생성하지 않는다.
     */
    @Transactional
    public void notifyLike(Post post, String likerUsername) {
        if (post.getAuthor().getUsername().equals(likerUsername)) return;
        Notification n = Notification.builder()
                .recipient(post.getAuthor())
                .type(NotificationType.LIKE)
                .message(likerUsername + "님이 회원님의 게시물을 좋아합니다.")
                .link("/posts/" + post.getId())
                .build();
        notificationRepository.save(n);
        pushUnreadCount(post.getAuthor().getUsername());
    }

    /**
     * 수신자의 전체 알림 목록을 최신순으로 반환하고,
     * 미읽음 알림을 모두 읽음으로 표시한다.
     */
    @Transactional
    public List<NotificationResponse> getAndMarkAllRead(String username) {
        User user = findUser(username);
        List<NotificationResponse> list = notificationRepository
                .findByRecipientOrderByCreatedAtDesc(user)
                .stream().map(NotificationResponse::new).collect(Collectors.toList());
        notificationRepository.markAllAsRead(user);
        // 읽음 처리 후 뱃지를 0으로 갱신
        sseEmitterService.sendCount(username, 0);
        return list;
    }

    /** 헤더 뱃지용: 미읽음 알림 수 */
    public long getUnreadCount(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return 0;
        return notificationRepository.countByRecipientAndIsReadFalse(user);
    }

    /* ── private helpers ──────────────────────────────────────── */

    /**
     * 현재 미읽음 수를 DB에서 조회해 SSE로 push한다.
     * 알림 저장 트랜잭션이 커밋된 후 push가 실행되므로 최신 카운트가 보장된다.
     */
    private void pushUnreadCount(String username) {
        long count = getUnreadCount(username);
        sseEmitterService.sendCount(username, count);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }
}
