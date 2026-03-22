package com.effectivedisco.service;

import com.effectivedisco.domain.Message;
import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.MessageRequest;
import com.effectivedisco.dto.response.MessageResponse;
import com.effectivedisco.repository.MessageRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock MessageRepository      messageRepository;
    @Mock UserRepository         userRepository;
    @Mock NotificationRepository notificationRepository;

    @InjectMocks MessageService messageService;

    // ── send ──────────────────────────────────────────────

    @Test
    void send_success_savesMessageAndCreatesNotification() {
        User sender    = makeUser("alice");
        User recipient = makeUser("bob");
        MessageRequest req = makeRequest("bob", "제목", "내용");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(sender));
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(recipient));
        // messageRepository.save()가 저장된 Message를 반환 (id 없이도 MessageResponse 생성 가능)
        given(messageRepository.save(any(Message.class)))
                .willAnswer(inv -> inv.getArgument(0));

        MessageResponse response = messageService.send(req, "alice");

        assertThat(response.getTitle()).isEqualTo("제목");
        verify(messageRepository).save(any(Message.class));
        // 수신자에게 알림도 생성되어야 한다
        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    void send_toSelf_throwsIllegalArgumentException() {
        User alice = makeUser("alice");
        MessageRequest req = makeRequest("alice", "제목", "내용");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(alice));

        assertThatThrownBy(() -> messageService.send(req, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신에게는 쪽지를 보낼 수 없습니다");
    }

    // ── getInbox ──────────────────────────────────────────

    @Test
    void getInbox_returnsReceivedMessages() {
        User bob = makeUser("bob");
        User alice = makeUser("alice");
        Message msg = makeMessage(1L, alice, bob);

        given(userRepository.findByUsername("bob")).willReturn(Optional.of(bob));
        given(messageRepository.findByRecipientAndDeletedByRecipientFalseOrderByCreatedAtDesc(bob))
                .willReturn(List.of(msg));

        List<MessageResponse> result = messageService.getInbox("bob");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("안녕");
    }

    // ── getDetail ─────────────────────────────────────────

    @Test
    void getDetail_byRecipient_marksAsRead() {
        User alice = makeUser("alice");
        User bob   = makeUser("bob");
        Message msg = makeMessage(1L, alice, bob); // alice → bob

        given(messageRepository.findById(1L)).willReturn(Optional.of(msg));

        // 수신자(bob)가 열람하면 읽음 처리
        messageService.getDetail(1L, "bob");

        assertThat(msg.isRead()).isTrue();
    }

    @Test
    void getDetail_bySender_doesNotMarkAsRead() {
        User alice = makeUser("alice");
        User bob   = makeUser("bob");
        Message msg = makeMessage(1L, alice, bob);

        given(messageRepository.findById(1L)).willReturn(Optional.of(msg));

        // 발신자(alice) 열람 시 읽음 처리 안 됨
        messageService.getDetail(1L, "alice");

        assertThat(msg.isRead()).isFalse();
    }

    @Test
    void getDetail_byThirdParty_throwsAccessDeniedException() {
        User alice = makeUser("alice");
        User bob   = makeUser("bob");
        Message msg = makeMessage(1L, alice, bob);

        given(messageRepository.findById(1L)).willReturn(Optional.of(msg));

        assertThatThrownBy(() -> messageService.getDetail(1L, "carol"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getDetail_deletedBySender_throwsException() {
        User alice = makeUser("alice");
        User bob   = makeUser("bob");
        Message msg = makeMessage(1L, alice, bob);
        msg.deleteFromSent(); // 발신자가 삭제

        given(messageRepository.findById(1L)).willReturn(Optional.of(msg));

        assertThatThrownBy(() -> messageService.getDetail(1L, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("삭제된 쪽지");
    }

    // ── delete ────────────────────────────────────────────

    @Test
    void delete_fromInbox_byRecipient_setsDeletedByRecipientFlag() {
        User alice = makeUser("alice");
        User bob   = makeUser("bob");
        Message msg = makeMessage(1L, alice, bob);

        given(messageRepository.findById(1L)).willReturn(Optional.of(msg));

        messageService.delete(1L, "bob", "inbox");

        assertThat(msg.isDeletedByRecipient()).isTrue();
    }

    @Test
    void delete_fromInbox_byNonRecipient_throwsAccessDeniedException() {
        User alice = makeUser("alice");
        User bob   = makeUser("bob");
        Message msg = makeMessage(1L, alice, bob);

        given(messageRepository.findById(1L)).willReturn(Optional.of(msg));

        // alice는 발신자이므로 받은 편지함에서 삭제 권한 없음
        assertThatThrownBy(() -> messageService.delete(1L, "alice", "inbox"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_fromSent_bySender_setsDeletedBySenderFlag() {
        User alice = makeUser("alice");
        User bob   = makeUser("bob");
        Message msg = makeMessage(1L, alice, bob);

        given(messageRepository.findById(1L)).willReturn(Optional.of(msg));

        messageService.delete(1L, "alice", "sent");

        assertThat(msg.isDeletedBySender()).isTrue();
    }

    // ── helpers ───────────────────────────────────────────

    private User makeUser(String username) {
        return User.builder().username(username).email(username + "@test.com").password("pw").build();
    }

    private Message makeMessage(Long id, User sender, User recipient) {
        Message msg = Message.builder()
                .sender(sender).recipient(recipient).title("안녕").content("내용").build();
        ReflectionTestUtils.setField(msg, "id", id);
        return msg;
    }

    private MessageRequest makeRequest(String to, String title, String content) {
        MessageRequest req = new MessageRequest();
        req.setTo(to);
        req.setTitle(title);
        req.setContent(content);
        return req;
    }
}
