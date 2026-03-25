package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.NotificationPageState;
import com.effectivedisco.dto.response.NotificationResponse;
import com.effectivedisco.event.NotificationRequestedEvent;
import com.effectivedisco.loadtest.NoOpLoadTestStepProfiler;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
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
                eventPublisher,
                new NoOpLoadTestStepProfiler()
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
    void storeNotificationAfterCommit_savesNotification_incrementsCounter_andPushesRefreshedCount() {
        User recipient = makeUser("alice");
        NotificationRequestedEvent event = new NotificationRequestedEvent(
                "alice",
                NotificationType.LIKE,
                "좋아요!",
                "/posts/1"
        );

        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 2L)));
        given(userRepository.getReferenceById(11L)).willReturn(recipient);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(3L));

        notificationService.storeNotificationAfterCommit(event);

        verify(notificationRepository).save(any(Notification.class));
        verify(userRepository).incrementUnreadNotificationCount(11L);
        verify(userRepository).findUnreadNotificationCountByUsername("alice");
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
        given(userRepository.findNotificationRecipientSnapshotByUsername("ghost")).willReturn(Optional.empty());

        notificationService.storeNotificationAfterCommit(event);

        verify(notificationRepository, never()).save(any());
        verify(sseEmitterService, never()).sendCount(any(), anyLong());
    }

    // ── getAndMarkAllRead ─────────────────────────────────

    @Test
    void getAndMarkAllRead_returnsList_marksRead_decrementsCounterByTransitionedRows() {
        User user = makeUser("alice");
        NotificationResponse notification = new NotificationResponse(
                1L,
                NotificationType.LIKE,
                "좋아요!",
                "/posts/1",
                false,
                LocalDateTime.now()
        );

        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 1L)));
        given(userRepository.getReferenceById(11L)).willReturn(user);
        given(notificationRepository.findResponseByRecipientOrderByCreatedAtDesc(user)).willReturn(List.of(notification));
        given(notificationRepository.findLatestNotificationIdByRecipientId(11L)).willReturn(1L);
        given(notificationRepository.markAllAsReadUpToId(11L, 1L)).willReturn(1);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(0L));

        List<NotificationResponse> result = notificationService.getAndMarkAllRead("alice");

        assertThat(result).hasSize(1);
        assertThat(result).allMatch(NotificationResponse::isRead);
        verify(notificationRepository).markAllAsReadUpToId(11L, 1L);
        verify(userRepository).decrementUnreadNotificationCount(11L, 1L);
        verify(sseEmitterService).sendCount("alice", 0L);
    }

    @Test
    void getAndMarkAllRead_whenUnreadAlreadyZero_skipsBulkUpdate() {
        User user = makeUser("alice");
        NotificationResponse notification = new NotificationResponse(
                2L,
                NotificationType.LIKE,
                "이미 읽은 알림",
                "/posts/1",
                true,
                LocalDateTime.now()
        );

        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 0L)));
        given(userRepository.getReferenceById(11L)).willReturn(user);
        given(notificationRepository.findResponseByRecipientOrderByCreatedAtDesc(user)).willReturn(List.of(notification));
        given(notificationRepository.findLatestNotificationIdByRecipientId(11L)).willReturn(2L);
        given(notificationRepository.existsByRecipientIdAndIsReadFalse(11L)).willReturn(false);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(0L));

        List<NotificationResponse> result = notificationService.getAndMarkAllRead("alice");

        assertThat(result).hasSize(1);
        verify(notificationRepository).existsByRecipientIdAndIsReadFalse(11L);
        verify(notificationRepository, never()).markAllAsReadUpToId(anyLong(), anyLong());
        verify(sseEmitterService).sendCount("alice", 0L);
    }

    @Test
    void getAndMarkAllRead_whenCounterDriftExists_fallsBackToUnreadRowExistenceCheck() {
        User user = makeUser("alice");
        NotificationResponse notification = new NotificationResponse(
                3L,
                NotificationType.COMMENT,
                "counter drift",
                "/posts/1",
                false,
                LocalDateTime.now()
        );

        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 0L)));
        given(userRepository.getReferenceById(11L)).willReturn(user);
        given(notificationRepository.findResponseByRecipientOrderByCreatedAtDesc(user)).willReturn(List.of(notification));
        given(notificationRepository.findLatestNotificationIdByRecipientId(11L)).willReturn(3L);
        given(notificationRepository.existsByRecipientIdAndIsReadFalse(11L)).willReturn(true);
        given(notificationRepository.markAllAsReadUpToId(11L, 3L)).willReturn(1);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(0L));

        List<NotificationResponse> result = notificationService.getAndMarkAllRead("alice");

        assertThat(result).hasSize(1);
        verify(notificationRepository).existsByRecipientIdAndIsReadFalse(11L);
        verify(notificationRepository).markAllAsReadUpToId(11L, 3L);
        verify(userRepository).decrementUnreadNotificationCount(11L, 1L);
        verify(sseEmitterService).sendCount("alice", 0L);
    }

    @Test
    void getPage_fetchesCurrentBatchWithoutReadTransition() {
        User user = makeUser("alice");
        NotificationResponse notification = new NotificationResponse(
                4L,
                NotificationType.MESSAGE,
                "페이지 알림",
                "/messages/1",
                false,
                LocalDateTime.now()
        );

        Slice<NotificationResponse> slice = new SliceImpl<>(
                List.of(notification),
                PageRequest.of(1, 20),
                true
        );

        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 1L)));
        given(userRepository.getReferenceById(11L)).willReturn(user);
        given(notificationRepository.findResponseSliceByRecipientOrderByCreatedAtDesc(user, PageRequest.of(1, 20)))
                .willReturn(slice);

        Slice<NotificationResponse> result = notificationService.getPage("alice", 1, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent()).anyMatch(n -> !n.isRead());
        verify(notificationRepository).findResponseSliceByRecipientOrderByCreatedAtDesc(user, PageRequest.of(1, 20));
        verify(notificationRepository, never()).markAllAsReadUpToId(anyLong(), anyLong());
    }

    @Test
    void markAllAsRead_executesTransitionWithoutFetchingList() {
        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 2L)));
        given(notificationRepository.findLatestNotificationIdByRecipientId(11L)).willReturn(5L);
        given(notificationRepository.markAllAsReadUpToId(11L, 5L)).willReturn(2);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(0L));

        long transitioned = notificationService.markAllAsRead("alice");

        assertThat(transitioned).isEqualTo(2);
        verify(notificationRepository, never()).findResponseByRecipientOrderByCreatedAtDesc(any());
        verify(notificationRepository, never()).findResponseSliceByRecipientOrderByCreatedAtDesc(any(), any());
        verify(notificationRepository).markAllAsReadUpToId(11L, 5L);
        verify(userRepository).decrementUnreadNotificationCount(11L, 2L);
        verify(sseEmitterService).sendCount("alice", 0L);
    }

    @Test
    void markPageAsRead_marksOnlyVisibleUnreadIds() {
        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 3L)));
        given(notificationRepository.findPageStateSliceByRecipientIdOrderByCreatedAtDesc(11L, PageRequest.of(0, 20)))
                .willReturn(new SliceImpl<>(
                        List.of(new NotificationPageState(7L, false), new NotificationPageState(8L, true)),
                        PageRequest.of(0, 20),
                        true
                ));
        given(notificationRepository.markPageAsReadByIds(11L, List.of(7L))).willReturn(1);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(2L));

        int transitioned = notificationService.markPageAsRead("alice", 0, 20);

        assertThat(transitioned).isEqualTo(1);
        verify(notificationRepository).markPageAsReadByIds(11L, List.of(7L));
        verify(userRepository).refreshUnreadNotificationCount(11L);
        verify(sseEmitterService).sendCount("alice", 2L);
    }

    @Test
    void markPageAsReadForLoadTest_usesVisibleBatchTransitionSummary() {
        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 2L)));
        given(notificationRepository.findPageStateSliceByRecipientIdOrderByCreatedAtDesc(11L, PageRequest.of(0, 20)))
                .willReturn(new SliceImpl<>(
                        List.of(new NotificationPageState(9L, false)),
                        PageRequest.of(0, 20),
                        false
                ));
        given(notificationRepository.markPageAsReadByIds(11L, List.of(9L))).willReturn(1);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(1L));

        NotificationService.NotificationReadSummary summary =
                notificationService.markPageAsReadForLoadTest("alice", 0, 20);

        assertThat(summary.listedNotificationCount()).isEqualTo(1);
        assertThat(summary.unreadCount()).isZero();
        verify(notificationRepository).markPageAsReadByIds(11L, List.of(9L));
        verify(userRepository).refreshUnreadNotificationCount(11L);
        verify(sseEmitterService).sendCount("alice", 1L);
    }

    @Test
    void markPageAsRead_whenConcurrentTransitionWins_refreshesCounterInsteadOfBlindDelta() {
        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 2L)));
        given(notificationRepository.findPageStateSliceByRecipientIdOrderByCreatedAtDesc(11L, PageRequest.of(0, 20)))
                .willReturn(new SliceImpl<>(
                        List.of(new NotificationPageState(10L, false)),
                        PageRequest.of(0, 20),
                        false
                ));
        given(notificationRepository.markPageAsReadByIds(11L, List.of(10L))).willReturn(0);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(1L));

        int transitioned = notificationService.markPageAsRead("alice", 0, 20);

        assertThat(transitioned).isZero();
        verify(notificationRepository).markPageAsReadByIds(11L, List.of(10L));
        verify(userRepository).refreshUnreadNotificationCount(11L);
        verify(userRepository, never()).decrementUnreadNotificationCount(anyLong(), anyLong());
        verify(sseEmitterService).sendCount("alice", 1L);
    }

    @Test
    void getAndMarkAllReadPage_fetchesCurrentBatchWithoutFullListMaterialize() {
        User user = makeUser("alice");
        NotificationResponse notification = new NotificationResponse(
                4L,
                NotificationType.MESSAGE,
                "페이지 알림",
                "/messages/1",
                false,
                LocalDateTime.now()
        );

        Slice<NotificationResponse> slice = new SliceImpl<>(
                List.of(notification),
                PageRequest.of(1, 20),
                true
        );

        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 1L)));
        given(userRepository.getReferenceById(11L)).willReturn(user);
        given(notificationRepository.findResponseSliceByRecipientOrderByCreatedAtDesc(user, PageRequest.of(1, 20)))
                .willReturn(slice);
        given(notificationRepository.findLatestNotificationIdByRecipientId(11L)).willReturn(4L);
        given(notificationRepository.markAllAsReadUpToId(11L, 4L)).willReturn(1);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(0L));

        Slice<NotificationResponse> result = notificationService.getAndMarkAllReadPage("alice", 1, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent()).allMatch(NotificationResponse::isRead);
        verify(notificationRepository).findResponseSliceByRecipientOrderByCreatedAtDesc(user, PageRequest.of(1, 20));
        verify(notificationRepository).markAllAsReadUpToId(11L, 4L);
        verify(notificationRepository, never()).findResponseByRecipientOrderByCreatedAtDesc(user);
    }

    @Test
    void markAllAsReadForLoadTest_usesTransitionSummaryWithoutFullCountQuery() {
        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 2L)));
        given(notificationRepository.findLatestNotificationIdByRecipientId(11L)).willReturn(5L);
        given(notificationRepository.markAllAsReadUpToId(11L, 5L)).willReturn(2);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(0L));

        NotificationService.NotificationReadSummary summary =
                notificationService.markAllAsReadForLoadTest("alice");

        assertThat(summary.listedNotificationCount()).isEqualTo(2);
        assertThat(summary.unreadCount()).isZero();
        verify(notificationRepository).markAllAsReadUpToId(11L, 5L);
        verify(userRepository).decrementUnreadNotificationCount(11L, 2L);
        verify(sseEmitterService).sendCount("alice", 0L);
    }

    @Test
    void getAndMarkAllRead_whenCounterDriftHasPositiveCounterButNoUnreadRows_reconcilesCounterToActualRows() {
        User user = makeUser("alice");
        NotificationResponse notification = new NotificationResponse(
                6L,
                NotificationType.MESSAGE,
                "stale counter",
                "/messages/1",
                true,
                LocalDateTime.now()
        );

        given(userRepository.findNotificationRecipientSnapshotByUsername("alice"))
                .willReturn(Optional.of(notificationRecipient(11L, "alice", 3L)));
        given(userRepository.getReferenceById(11L)).willReturn(user);
        given(notificationRepository.findResponseByRecipientOrderByCreatedAtDesc(user)).willReturn(List.of(notification));
        given(notificationRepository.findLatestNotificationIdByRecipientId(11L)).willReturn(6L);
        given(notificationRepository.markAllAsReadUpToId(11L, 6L)).willReturn(0);
        given(notificationRepository.countUnreadByRecipientId(11L)).willReturn(0L);
        given(userRepository.findUnreadNotificationCountByUsername("alice")).willReturn(Optional.of(0L));

        List<NotificationResponse> result = notificationService.getAndMarkAllRead("alice");

        assertThat(result).hasSize(1);
        verify(userRepository).setUnreadNotificationCount(11L, 0L);
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

    private UserRepository.NotificationRecipientSnapshot notificationRecipient(Long id, String username, long unreadCount) {
        return new UserRepository.NotificationRecipientSnapshot() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public long getUnreadNotificationCount() {
                return unreadCount;
            }
        };
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
