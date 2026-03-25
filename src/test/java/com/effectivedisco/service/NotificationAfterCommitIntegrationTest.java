package com.effectivedisco.service;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.event.NotificationRequestedEvent;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.BookmarkRepository;
import com.effectivedisco.repository.FollowRepository;
import com.effectivedisco.repository.MessageRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NotificationAfterCommitIntegrationTest {

    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PostLikeRepository postLikeRepository;
    @Autowired BookmarkRepository bookmarkRepository;
    @Autowired BlockRepository blockRepository;
    @Autowired FollowRepository followRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired PostRepository postRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        postLikeRepository.deleteAll();
        bookmarkRepository.deleteAll();
        blockRepository.deleteAll();
        followRepository.deleteAll();
        messageRepository.deleteAll();
        notificationRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void notifyLike_afterCommit_createsNotificationAndIncrementsUnreadCounter() {
        User author = saveUser("author");
        Post post = savePost(author);

        transactionTemplate.executeWithoutResult(status ->
                notificationService.notifyLike(post, "liker"));

        User persistedAuthor = userRepository.findByUsername("author").orElseThrow();

        assertThat(notificationRepository.findByRecipientOrderByCreatedAtDesc(persistedAuthor))
                .as("문제 해결 검증: 커밋된 트랜잭션만 AFTER_COMMIT 리스너를 통해 알림을 생성해야 한다")
                .hasSize(1);
        assertThat(userRepository.findUnreadNotificationCountByUsername("author"))
                .as("문제 해결 검증: unread counter도 알림 생성과 함께 1 증가해야 한다")
                .hasValue(1L);
    }

    @Test
    void notifyLike_rollback_doesNotCreateNotificationOrIncrementCounter() {
        User author = saveUser("author");
        Post post = savePost(author);

        transactionTemplate.executeWithoutResult(status -> {
            notificationService.notifyLike(post, "liker");
            status.setRollbackOnly();
        });

        User persistedAuthor = userRepository.findByUsername("author").orElseThrow();

        assertThat(notificationRepository.findByRecipientOrderByCreatedAtDesc(persistedAuthor))
                .as("문제 해결 검증: 롤백된 트랜잭션에서는 알림이 생성되면 안 된다")
                .isEmpty();
        assertThat(userRepository.findUnreadNotificationCountByUsername("author"))
                .as("문제 해결 검증: 롤백된 트랜잭션에서는 unread counter도 증가하면 안 된다")
                .hasValue(0L);
    }

    @Test
    void storeNotificationAfterCommit_concurrentRequests_incrementUnreadCounterExactlyOncePerNotification() throws Exception {
        User recipient = saveUser("recipient");

        runConcurrently(12, index -> notificationService.storeNotificationAfterCommit(
                new NotificationRequestedEvent(
                        recipient.getUsername(),
                        NotificationType.MESSAGE,
                        "message-" + index,
                        "/messages/" + index
                )
        ));

        User persistedRecipient = userRepository.findByUsername(recipient.getUsername()).orElseThrow();

        assertThat(notificationRepository.findByRecipientOrderByCreatedAtDesc(persistedRecipient))
                .as("문제 해결 검증: 동시 알림 생성 시에도 알림 row는 요청 수만큼 빠짐없이 저장되어야 한다")
                .hasSize(12);
        assertThat(userRepository.findUnreadNotificationCountByUsername(recipient.getUsername()))
                .as("문제 해결 검증: unread counter는 동시 알림 생성 수와 정확히 같아야 한다")
                .hasValue(12L);
    }

    @Test
    void notificationCreateAndMarkAllRead_concurrentRequests_keepUnreadCounterAlignedWithUnreadRows() throws Exception {
        User recipient = saveUser("recipient");

        List<ThrowingRunnable> actions = new ArrayList<>();
        for (int writer = 0; writer < 6; writer++) {
            int writerIndex = writer;
            actions.add(() -> {
                for (int i = 0; i < 6; i++) {
                    notificationService.storeNotificationAfterCommit(new NotificationRequestedEvent(
                            recipient.getUsername(),
                            NotificationType.MESSAGE,
                            "writer-" + writerIndex + "-" + i,
                            "/messages/" + writerIndex + "-" + i
                    ));
                    Thread.yield();
                }
            });
        }
        for (int reader = 0; reader < 3; reader++) {
            actions.add(() -> {
                for (int i = 0; i < 6; i++) {
                    notificationService.getAndMarkAllRead(recipient.getUsername());
                    Thread.yield();
                }
            });
        }

        runConcurrently(actions);

        User persistedRecipient = userRepository.findByUsername(recipient.getUsername()).orElseThrow();
        long unreadRows = notificationRepository.countByRecipientAndIsReadFalse(persistedRecipient);
        long unreadCounter = userRepository.findUnreadNotificationCountByUsername(recipient.getUsername()).orElse(0L);

        assertThat(unreadCounter)
                .as("문제 해결 검증: 읽음 처리와 새 알림 생성이 동시에 일어나도 unread counter는 실제 unread row 수와 일치해야 한다")
                .isEqualTo(unreadRows);
    }

    @Test
    void notificationCreateAndMarkPageRead_concurrentRequests_keepUnreadCounterAlignedWithUnreadRows() throws Exception {
        User recipient = saveUser("recipient");
        for (int i = 0; i < 12; i++) {
            notificationService.storeNotificationAfterCommit(new NotificationRequestedEvent(
                    recipient.getUsername(),
                    NotificationType.MESSAGE,
                    "seed-" + i,
                    "/messages/seed-" + i
            ));
        }

        List<ThrowingRunnable> actions = new ArrayList<>();
        for (int writer = 0; writer < 4; writer++) {
            int writerIndex = writer;
            actions.add(() -> {
                for (int i = 0; i < 10; i++) {
                    notificationService.storeNotificationAfterCommit(new NotificationRequestedEvent(
                            recipient.getUsername(),
                            NotificationType.MESSAGE,
                            "page-writer-" + writerIndex + "-" + i,
                            "/messages/page-writer-" + writerIndex + "-" + i
                    ));
                    Thread.yield();
                }
            });
        }
        for (int reader = 0; reader < 3; reader++) {
            actions.add(() -> {
                for (int i = 0; i < 8; i++) {
                    notificationService.markPageAsReadForLoadTest(recipient.getUsername(), 0, 20);
                    Thread.yield();
                }
            });
        }

        runConcurrently(actions);

        User persistedRecipient = userRepository.findByUsername(recipient.getUsername()).orElseThrow();
        long unreadRows = notificationRepository.countByRecipientAndIsReadFalse(persistedRecipient);
        long unreadCounter = userRepository.findUnreadNotificationCountByUsername(recipient.getUsername()).orElse(0L);

        assertThat(unreadCounter)
                .as("문제 해결 검증: read-page 와 새 알림 생성이 동시에 일어나도 unread counter는 실제 unread row 수와 일치해야 한다")
                .isEqualTo(unreadRows);
    }

    private User saveUser(String username) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.com")
                .password(passwordEncoder.encode("pass"))
                .build());
    }

    private Post savePost(User author) {
        return postRepository.save(Post.builder()
                .title("title")
                .content("content")
                .author(author)
                .build());
    }

    private void runConcurrently(int workers, IndexedThrowingRunnable action) throws Exception {
        List<ThrowingRunnable> actions = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            int index = i;
            actions.add(() -> action.run(index));
        }
        runConcurrently(actions);
    }

    private void runConcurrently(List<ThrowingRunnable> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (ThrowingRunnable action : actions) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                action.run();
                return null;
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface IndexedThrowingRunnable {
        void run(int index) throws Exception;
    }
}
