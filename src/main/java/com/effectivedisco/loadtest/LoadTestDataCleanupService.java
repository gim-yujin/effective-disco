package com.effectivedisco.loadtest;

import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.CommentLikeRepository;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.MessageRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.PasswordResetTokenRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.ReportRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 문제 해결:
 * 현재 load test는 실제 회원/게시물/댓글을 생성하므로 같은 개발 DB를 공유하면
 * k6를 돌릴수록 데이터가 누적돼 다음 측정과 수동 확인이 모두 오염된다.
 * 실행 prefix(username 시작값) 기준으로 해당 런이 만든 사용자 범위를 회수해
 * 측정 직후 DB를 다시 깨끗한 상태로 되돌린다.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")
public class LoadTestDataCleanupService {

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final NotificationRepository notificationRepository;
    private final MessageRepository messageRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PostLikeRepository postLikeRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final ReportRepository reportRepository;
    private final BlockRepository blockRepository;

    @Transactional
    public CleanupSummary cleanupByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("load test cleanup prefix must not be blank");
        }

        List<User> users = userRepository.findByUsernameStartingWithOrderByIdAsc(prefix);
        long matchedUsers = users.size();
        long matchedPosts = postRepository.countByAuthorUsernameStartingWith(prefix);
        long matchedComments = commentRepository.countByAuthorUsernameStartingWith(prefix);
        long matchedNotifications = notificationRepository.countByRecipientUsernameStartingWith(prefix);
        long matchedMessages = messageRepository.countBySenderUsernameStartingWithOrRecipientUsernameStartingWith(prefix, prefix);
        long matchedPasswordResetTokens = passwordResetTokenRepository.countByUserUsernameStartingWith(prefix);

        for (User user : users) {
            passwordResetTokenRepository.deleteByUser(user);
            notificationRepository.deleteAllByRecipient(user);
            messageRepository.deleteAllByUser(user);
            postLikeRepository.deleteByUser(user);
            postLikeRepository.deleteByPostAuthor(user);
            commentLikeRepository.deleteByUser(user);
            commentLikeRepository.deleteByCommentAuthor(user);
            commentLikeRepository.deleteByPostAuthor(user);
            reportRepository.deleteByReporter(user);
            blockRepository.deleteAllByBlocker(user);
            blockRepository.deleteAllByBlocked(user);
            userRepository.delete(user);
        }

        userRepository.flush();

        return new CleanupSummary(
                prefix,
                matchedUsers,
                matchedPosts,
                matchedComments,
                matchedNotifications,
                matchedMessages,
                matchedPasswordResetTokens
        );
    }

    public record CleanupSummary(
            String prefix,
            long matchedUsers,
            long matchedPosts,
            long matchedComments,
            long matchedNotifications,
            long matchedMessages,
            long matchedPasswordResetTokens
    ) {}
}
