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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository  notificationRepository;
    @Mock UserRepository          userRepository;
    @Mock SseEmitterService       sseEmitterService;
    @Mock ApplicationEventPublisher eventPublisher;

    NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                notificationRepository,
                userRepository,
                sseEmitterService,
                eventPublisher
        );
    }

    // ── publish event ─────────────────────────────────────

    @Test
    void notifyComment_differentUser_publishesAfterCommitEvent() {
        User author = makeUser("author");
        Post post   = makePost(1L, author);

        notificationService.notifyComment(post, "commenter");

        verify(eventPublisher).publishEvent(new NotificationRequestedEvent(
                "author",
                NotificationType.COMMENT,
                "commenter님이 회원님의 게시물에 댓글을 남겼습니다.",
                "/posts/1#comments"
        ));
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void notifyComment_sameAuthor_doesNotPublishEvent() {
        User author = makeUser("author");
        Post post   = makePost(1L, author);

        notificationService.notifyComment(post, "author");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void notifyReply_differentUser_publishesAfterCommitEvent() {
        User commentAuthor = makeUser("commentAuthor");
        Post post          = makePost(1L, makeUser("postAuthor"));
        Comment parent     = makeComment(10L, post, commentAuthor);

        notificationService.notifyReply(parent, "replier");

        verify(eventPublisher).publishEvent(new NotificationRequestedEvent(
                "commentAuthor",
                NotificationType.REPLY,
                "replier님이 회원님의 댓글에 답글을 남겼습니다.",
                "/posts/1#comment-10"
        ));
    }

    @Test
    void notifyLike_differentUser_publishesAfterCommitEvent() {
        User author = makeUser("author");
        Post post   = makePost(1L, author);

        notificationService.notifyLike(post, "liker");

        verify(eventPublisher).publishEvent(new NotificationRequestedEvent(
                "author",
                NotificationType.LIKE,
                "liker님이 회원님의 게시물을 좋아합니다.",
                "/posts/1"
        ));
    }

    @Test
    void notifyMessage_publishesAfterCommitEvent() {
        notificationService.notifyMessage("bob", "alice", "제목", 7L);

        verify(eventPublisher).publishEvent(new NotificationRequestedEvent(
                "bob",
                NotificationType.MESSAGE,
                "alice님이 쪽지를 보냈습니다: 제목",
                "/messages/7"
        ));
    }

    // ── store after commit ───────────────────────────────

    @Test
    void storeNotificationAfterCommit_savesNotification_incrementsCounter_andPushesLatestCount() {
        User recipient = makeUser("alice");
        ReflectionTestUtils.setField(recipient, "id", 11L);
        NotificationRequestedEvent event = new NotificationRequestedEvent(
                "alice",
                NotificationType.LIKE,
                "좋아요!",
                "/posts/1"
        );

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(recipient));
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(3L));

        notificationService.storeNotificationAfterCommit(event);

        verify(notificationRepository).save(any(Notification.class));
        verify(userRepository).incrementUnreadNotificationCount(11L);
        verify(sseEmitterService).sendCount("alice", 3L);
    }

    @Test
    void storeNotificationAfterCommit_missingRecipient_ignoresEvent() {
        NotificationRequestedEvent event = new NotificationRequestedEvent(
                "ghost",
                NotificationType.LIKE,
                "좋아요!",
                "/posts/1"
        );
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        notificationService.storeNotificationAfterCommit(event);

        verify(notificationRepository, never()).save(any());
        verify(sseEmitterService, never()).sendCount(any(), anyLong());
    }

    // ── getAndMarkAllRead ─────────────────────────────────

    @Test
    void getAndMarkAllRead_returnsList_marksRead_resetsCounter_andPushesLatestCount() {
        User user = makeUser("alice");
        ReflectionTestUtils.setField(user, "id", 11L);
        Notification notification = Notification.builder()
                .recipient(user)
                .type(NotificationType.LIKE)
                .message("좋아요!")
                .link("/posts/1")
                .build();

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(0L));
        given(notificationRepository.findByRecipientOrderByCreatedAtDesc(user)).willReturn(List.of(notification));

        List<NotificationResponse> result = notificationService.getAndMarkAllRead("alice");

        assertThat(result).hasSize(1);
        verify(notificationRepository).markAllAsRead(user);
        verify(userRepository).resetUnreadNotificationCount(11L);
        verify(sseEmitterService).sendCount("alice", 0L);
    }

    // ── getUnreadCount ────────────────────────────────────

    @Test
    void getUnreadCount_returnsCounterValue() {
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(5L));

        assertThat(notificationService.getUnreadCount("alice")).isEqualTo(5L);
    }

    @Test
    void getUnreadCount_unknownUser_returnsZero() {
        given(userRepository.findUnreadNotificationCountByUsername("ghost")).willReturn(Optional.empty());

        assertThat(notificationService.getUnreadCount("ghost")).isZero();
    }

    private User makeUser(String username) {
        return User.builder().username(username).email(username + "@test.com").password("pw").build();
    }

    private Post makePost(Long id, User author) {
        Post post = Post.builder().title("T").content("C").author(author).build();
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }

    private Comment makeComment(Long id, Post post, User author) {
        Comment comment = Comment.builder().content("댓글").post(post).author(author).build();
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }
}
