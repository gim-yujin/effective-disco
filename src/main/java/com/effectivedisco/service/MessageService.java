package com.effectivedisco.service;

import com.effectivedisco.domain.Message;
import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.MessageRequest;
import com.effectivedisco.dto.response.MessageResponse;
import com.effectivedisco.repository.MessageRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository      messageRepository;
    private final UserRepository         userRepository;
    private final NotificationRepository notificationRepository;

    /**
     * 쪽지 전송.
     * 수신자에게 MESSAGE 타입 알림도 함께 생성한다.
     * 자기 자신에게는 쪽지를 보낼 수 없다.
     */
    @Transactional
    public MessageResponse send(MessageRequest request, String senderUsername) {
        User sender    = findUser(senderUsername);
        User recipient = findUser(request.getTo());

        if (sender.getUsername().equals(recipient.getUsername())) {
            throw new IllegalArgumentException("자기 자신에게는 쪽지를 보낼 수 없습니다.");
        }

        Message message = Message.builder()
                .sender(sender)
                .recipient(recipient)
                .title(request.getTitle())
                .content(request.getContent())
                .build();
        messageRepository.save(message);

        // 수신자에게 알림 생성
        Notification n = Notification.builder()
                .recipient(recipient)
                .type(NotificationType.MESSAGE)
                .message(senderUsername + "님이 쪽지를 보냈습니다: " + request.getTitle())
                .link("/messages/" + message.getId())
                .build();
        notificationRepository.save(n);

        return new MessageResponse(message);
    }

    /**
     * 받은 편지함.
     * 열람 시 개별 읽음 처리를 하지 않고 상세 조회 시에만 읽음 처리한다.
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> getInbox(String username) {
        User user = findUser(username);
        return messageRepository
                .findByRecipientAndDeletedByRecipientFalseOrderByCreatedAtDesc(user)
                .stream().map(MessageResponse::new).collect(Collectors.toList());
    }

    /**
     * 보낸 편지함.
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> getSent(String username) {
        User user = findUser(username);
        return messageRepository
                .findBySenderAndDeletedBySenderFalseOrderByCreatedAtDesc(user)
                .stream().map(MessageResponse::new).collect(Collectors.toList());
    }

    /**
     * 쪽지 상세 조회.
     * 수신자가 열람하면 읽음 처리한다.
     * 발신자·수신자 모두 조회할 수 있지만 삭제 처리된 쪽지는 접근 불가.
     */
    @Transactional
    public MessageResponse getDetail(Long id, String username) {
        Message message = findMessage(id);
        boolean isSender    = message.getSender().getUsername().equals(username);
        boolean isRecipient = message.getRecipient().getUsername().equals(username);

        if (!isSender && !isRecipient) {
            throw new AccessDeniedException("이 쪽지에 접근할 권한이 없습니다.");
        }
        if (isSender    && message.isDeletedBySender())    throw new IllegalArgumentException("삭제된 쪽지입니다.");
        if (isRecipient && message.isDeletedByRecipient()) throw new IllegalArgumentException("삭제된 쪽지입니다.");

        // 수신자가 열람 시 읽음 처리
        if (isRecipient && !message.isRead()) {
            message.markAsRead();
        }
        return new MessageResponse(message);
    }

    /**
     * 쪽지 삭제 (소프트 삭제).
     * box = "inbox" 이면 받은 편지함에서, "sent" 이면 보낸 편지함에서 삭제한다.
     */
    @Transactional
    public void delete(Long id, String username, String box) {
        Message message = findMessage(id);
        if ("inbox".equals(box)) {
            if (!message.getRecipient().getUsername().equals(username)) {
                throw new AccessDeniedException("삭제 권한이 없습니다.");
            }
            message.deleteFromInbox();
        } else {
            if (!message.getSender().getUsername().equals(username)) {
                throw new AccessDeniedException("삭제 권한이 없습니다.");
            }
            message.deleteFromSent();
        }
    }

    /** 헤더 뱃지용: 읽지 않은 수신 메시지 수 */
    public long getUnreadCount(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return 0;
        return messageRepository.countByRecipientAndIsReadFalseAndDeletedByRecipientFalse(user);
    }

    private Message findMessage(Long id) {
        return messageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("쪽지를 찾을 수 없습니다: " + id));
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }
}
