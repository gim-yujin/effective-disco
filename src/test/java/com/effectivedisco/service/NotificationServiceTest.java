package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.NotificationResponse;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * NotificationService 단위 테스트.
 *
 * sseEmitterService 는 @RequiredArgsConstructor 생성자에 포함되지 않는 비-final 필드이므로
 * @InjectMocks 가 생성자 주입 후 필드 주입으로 목을 삽입한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock UserRepository          userRepository;
    @Mock SseEmitterService       sseEmitterService;

    NotificationService notificationService;

    /**
     * @RequiredArgsConstructor가 생성한 생성자(notificationRepository, userRepository)로
     * 서비스를 직접 생성한 뒤, @Lazy @Autowired 비-final 필드인 sseEmitterService를
     * ReflectionTestUtils로 수동 주입한다.
     * @InjectMocks는 @Lazy @Autowired 필드를 신뢰성 있게 주입하지 못하는 경우가 있다.
     */
    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationRepository, userRepository);
        ReflectionTestUtils.setField(notificationService, "sseEmitterService", sseEmitterService);
    }

    // ── notifyComment ─────────────────────────────────────

    @Test
    void notifyComment_differentUser_savesNotificationAndPushesSSE() {
        User author    = makeUser("author");
        User commenter = makeUser("commenter");
        Post post = makePost(1L, author);

        // pushUnreadCount 내부: userRepository.findByUsername + countByRecipientAndIsReadFalse
        given(userRepository.findByUsername("author")).willReturn(Optional.of(author));
        given(notificationRepository.countByRecipientAndIsReadFalse(author)).willReturn(1L);

        notificationService.notifyComment(post, "commenter");

        verify(notificationRepository).save(any(Notification.class));
        verify(sseEmitterService).sendCount("author", 1L);
    }

    @Test
    void notifyComment_sameAuthorAsCommenter_doesNotSave() {
        // 본인 게시물에 본인이 댓글 → 알림 없음
        User author = makeUser("author");
        Post post   = makePost(1L, author);

        notificationService.notifyComment(post, "author");

        verify(notificationRepository, never()).save(any());
        verify(sseEmitterService, never()).sendCount(anyString(), anyLong());
    }

    // ── notifyReply ───────────────────────────────────────

    @Test
    void notifyReply_differentUser_savesNotificationAndPushesSSE() {
        User commentAuthor = makeUser("commentAuthor");
        User replier       = makeUser("replier");
        Post post    = makePost(1L, makeUser("postAuthor"));
        Comment parent = makeComment(10L, post, commentAuthor);

        given(userRepository.findByUsername("commentAuthor")).willReturn(Optional.of(commentAuthor));
        given(notificationRepository.countByRecipientAndIsReadFalse(commentAuthor)).willReturn(2L);

        notificationService.notifyReply(parent, "replier");

        verify(notificationRepository).save(any(Notification.class));
        verify(sseEmitterService).sendCount("commentAuthor", 2L);
    }

    @Test
    void notifyReply_sameAuthorAsReplier_doesNotSave() {
        User commentAuthor = makeUser("commentAuthor");
        Post post   = makePost(1L, makeUser("postAuthor"));
        Comment parent = makeComment(10L, post, commentAuthor);

        notificationService.notifyReply(parent, "commentAuthor");

        verify(notificationRepository, never()).save(any());
    }

    // ── notifyLike ────────────────────────────────────────

    @Test
    void notifyLike_differentUser_savesNotificationAndPushesSSE() {
        User author = makeUser("author");
        Post post   = makePost(1L, author);

        given(userRepository.findByUsername("author")).willReturn(Optional.of(author));
        given(notificationRepository.countByRecipientAndIsReadFalse(author)).willReturn(3L);

        notificationService.notifyLike(post, "liker");

        verify(notificationRepository).save(any(Notification.class));
        verify(sseEmitterService).sendCount("author", 3L);
    }

    @Test
    void notifyLike_sameAuthorAsLiker_doesNotSave() {
        User author = makeUser("author");
        Post post   = makePost(1L, author);

        notificationService.notifyLike(post, "author");

        verify(notificationRepository, never()).save(any());
    }

    // ── getAndMarkAllRead ─────────────────────────────────

    @Test
    void getAndMarkAllRead_returnsListAndMarksReadAndPushesZero() {
        User user = makeUser("alice");
        Notification n = Notification.builder()
                .recipient(user).type(NotificationType.LIKE)
                .message("좋아요!").link("/posts/1").build();

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(notificationRepository.findByRecipientOrderByCreatedAtDesc(user))
                .willReturn(List.of(n));

        List<NotificationResponse> result = notificationService.getAndMarkAllRead("alice");

        assertThat(result).hasSize(1);
        verify(notificationRepository).markAllAsRead(user);
        // 읽음 처리 후 SSE로 count=0 push
        verify(sseEmitterService).sendCount("alice", 0);
    }

    // ── getUnreadCount ────────────────────────────────────

    @Test
    void getUnreadCount_returnsCountFromRepository() {
        User user = makeUser("alice");
        given(userRepository.findByUsername("alice")).willReturn(Optional.of(user));
        given(notificationRepository.countByRecipientAndIsReadFalse(user)).willReturn(5L);

        assertThat(notificationService.getUnreadCount("alice")).isEqualTo(5L);
    }

    @Test
    void getUnreadCount_unknownUser_returnsZero() {
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThat(notificationService.getUnreadCount("ghost")).isZero();
    }

    // ── helpers ───────────────────────────────────────────

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
