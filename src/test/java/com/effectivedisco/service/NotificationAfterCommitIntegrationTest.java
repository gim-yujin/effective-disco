package com.effectivedisco.service;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
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
}
