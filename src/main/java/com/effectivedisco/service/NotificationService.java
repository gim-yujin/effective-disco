package com.effectivedisco.service;

import com.effectivedisco.domain.*;
import com.effectivedisco.dto.response.NotificationResponse;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
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
        return list;
    }

    /** 헤더 뱃지용: 미읽음 알림 수 */
    public long getUnreadCount(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return 0;
        return notificationRepository.countByRecipientAndIsReadFalse(user);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }
}
