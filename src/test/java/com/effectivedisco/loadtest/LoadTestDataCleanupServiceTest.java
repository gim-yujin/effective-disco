package com.effectivedisco.loadtest;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.CommentLike;
import com.effectivedisco.domain.Message;
import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.PasswordResetToken;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostLike;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.CommentLikeRepository;
import com.effectivedisco.repository.MessageRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.PasswordResetTokenRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.load-test.enabled=true")
class LoadTestDataCleanupServiceTest {

    @Autowired LoadTestDataCleanupService loadTestDataCleanupService;
    @Autowired UserRepository userRepository;
    @Autowired PostRepository postRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired CommentLikeRepository commentLikeRepository;
    @Autowired PostLikeRepository postLikeRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;

    @Test
    void cleanupByPrefix_removesLoadtestUsersAndDependentRows() {
        User loadtestAuthor = userRepository.saveAndFlush(new User("ltcase-u0", "ltcase-u0@test.local", "pw"));
        User loadtestWriter = userRepository.saveAndFlush(new User("ltcase-u1", "ltcase-u1@test.local", "pw"));
        User regularUser = userRepository.saveAndFlush(new User("regular-u0", "regular-u0@test.local", "pw"));

        Post loadtestPost = postRepository.saveAndFlush(Post.builder()
                .title("ltcase post")
                .content("ltcase content")
                .author(loadtestAuthor)
                .board(null)
                .build());
        Post regularPost = postRepository.saveAndFlush(Post.builder()
                .title("regular post")
                .content("regular content")
                .author(regularUser)
                .board(null)
                .build());

        Comment loadtestCommentOnLoadtestPost = commentRepository.saveAndFlush(Comment.builder()
                .content("ltcase comment")
                .post(loadtestPost)
                .author(loadtestWriter)
                .parent(null)
                .build());
        Comment regularCommentOnLoadtestPost = commentRepository.saveAndFlush(Comment.builder()
                .content("regular comment on ltcase post")
                .post(loadtestPost)
                .author(regularUser)
                .parent(null)
                .build());
        Comment loadtestCommentOnRegularPost = commentRepository.saveAndFlush(Comment.builder()
                .content("ltcase comment on regular post")
                .post(regularPost)
                .author(loadtestWriter)
                .parent(null)
                .build());
        Comment regularCommentOnRegularPost = commentRepository.saveAndFlush(Comment.builder()
                .content("regular comment on regular post")
                .post(regularPost)
                .author(regularUser)
                .parent(null)
                .build());

        postLikeRepository.saveAndFlush(new PostLike(loadtestPost, regularUser));
        postLikeRepository.saveAndFlush(new PostLike(regularPost, loadtestWriter));
        commentLikeRepository.saveAndFlush(new CommentLike(loadtestCommentOnLoadtestPost, regularUser));
        commentLikeRepository.saveAndFlush(new CommentLike(regularCommentOnLoadtestPost, regularUser));
        commentLikeRepository.saveAndFlush(new CommentLike(loadtestCommentOnRegularPost, regularUser));
        commentLikeRepository.saveAndFlush(new CommentLike(regularCommentOnRegularPost, loadtestWriter));

        notificationRepository.saveAndFlush(Notification.builder()
                .recipient(loadtestAuthor)
                .type(NotificationType.COMMENT)
                .message("ltcase notification")
                .link("/posts/1")
                .build());

        messageRepository.saveAndFlush(Message.builder()
                .sender(loadtestWriter)
                .recipient(regularUser)
                .title("ltcase message")
                .content("hello")
                .build());

        passwordResetTokenRepository.saveAndFlush(new PasswordResetToken(
                "ltcase-token",
                loadtestAuthor,
                LocalDateTime.now().plusHours(1)
        ));

        LoadTestDataCleanupService.CleanupSummary summary = loadTestDataCleanupService.cleanupByPrefix("ltcase");

        assertThat(summary.matchedUsers()).isEqualTo(2);
        assertThat(summary.matchedPosts()).isEqualTo(1);
        assertThat(summary.matchedComments())
                .as("문제 해결 검증: prefix 사용자 작성 댓글 수를 먼저 집계해 cleanup 범위를 확인할 수 있어야 한다")
                .isEqualTo(2);
        assertThat(summary.matchedNotifications()).isEqualTo(1);
        assertThat(summary.matchedMessages()).isEqualTo(1);
        assertThat(summary.matchedPasswordResetTokens()).isEqualTo(1);

        assertThat(userRepository.findByUsername("ltcase-u0")).isEmpty();
        assertThat(userRepository.findByUsername("ltcase-u1")).isEmpty();
        assertThat(userRepository.findByUsername("regular-u0")).isPresent();

        assertThat(postRepository.countByAuthorUsernameStartingWith("ltcase")).isZero();
        assertThat(commentRepository.count()).isEqualTo(1);
        assertThat(postLikeRepository.count()).isZero();
        assertThat(commentLikeRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
        assertThat(messageRepository.count()).isZero();
        assertThat(passwordResetTokenRepository.count()).isZero();
        assertThat(postRepository.countByAuthorUsernameStartingWith("regular")).isEqualTo(1);
    }
}
