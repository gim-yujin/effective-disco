package com.effectivedisco.service;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.BookmarkRepository;
import com.effectivedisco.repository.FollowRepository;
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
class WritePathConcurrencyTest {

    @Autowired PostService postService;
    @Autowired FollowService followService;
    @Autowired BookmarkService bookmarkService;
    @Autowired BlockService blockService;

    @Autowired UserRepository userRepository;
    @Autowired PostRepository postRepository;
    @Autowired PostLikeRepository postLikeRepository;
    @Autowired FollowRepository followRepository;
    @Autowired BookmarkRepository bookmarkRepository;
    @Autowired BlockRepository blockRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        postLikeRepository.deleteAll();
        bookmarkRepository.deleteAll();
        blockRepository.deleteAll();
        followRepository.deleteAll();
        notificationRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void likePost_concurrentDuplicateRequests_createSingleLikeAndCountOnce() throws Exception {
        User author = saveUser("author");
        User actor  = saveUser("actor");
        Post post   = savePost(author, "동시 좋아요");

        runConcurrently(8, () -> postService.likePost(post.getId(), actor.getUsername()));

        Post persistedPost = postRepository.findById(post.getId()).orElseThrow();
        User persistedUser = userRepository.findByUsername(actor.getUsername()).orElseThrow();

        assertThat(postLikeRepository.countByPostAndUser(persistedPost, persistedUser))
                .as("문제 해결 검증: 같은 좋아요 요청이 동시에 여러 번 와도 좋아요 row는 1개여야 한다")
                .isEqualTo(1);
        assertThat(postRepository.findLikeCountById(post.getId()))
                .as("문제 해결 검증: likeCount도 중복 증가 없이 1이어야 한다")
                .isEqualTo(1);
    }

    @Test
    void unlikePost_concurrentDuplicateRequests_removeSingleLikeAndCountOnce() throws Exception {
        User author = saveUser("author");
        User actor  = saveUser("actor");
        Post post   = savePost(author, "동시 좋아요 해제");
        postService.likePost(post.getId(), actor.getUsername());

        runConcurrently(8, () -> postService.unlikePost(post.getId(), actor.getUsername()));

        Post persistedPost = postRepository.findById(post.getId()).orElseThrow();
        User persistedUser = userRepository.findByUsername(actor.getUsername()).orElseThrow();

        assertThat(postLikeRepository.countByPostAndUser(persistedPost, persistedUser))
                .as("문제 해결 검증: 같은 좋아요 해제 요청이 동시에 와도 좋아요 row는 0개여야 한다")
                .isZero();
        assertThat(postRepository.findLikeCountById(post.getId()))
                .as("문제 해결 검증: likeCount는 음수가 되지 않고 0으로 유지되어야 한다")
                .isZero();
    }

    @Test
    void follow_concurrentDuplicateRequests_createSingleRelation() throws Exception {
        User follower  = saveUser("follower");
        User following = saveUser("following");

        runConcurrently(8, () -> followService.follow(follower.getUsername(), following.getUsername()));

        User persistedFollower  = userRepository.findByUsername(follower.getUsername()).orElseThrow();
        User persistedFollowing = userRepository.findByUsername(following.getUsername()).orElseThrow();

        assertThat(followRepository.countByFollowerAndFollowing(persistedFollower, persistedFollowing))
                .as("문제 해결 검증: 같은 팔로우 요청이 동시에 와도 관계는 1개만 생성되어야 한다")
                .isEqualTo(1);
    }

    @Test
    void bookmark_concurrentDuplicateRequests_createSingleRelation() throws Exception {
        User author = saveUser("author");
        User actor  = saveUser("actor");
        Post post   = savePost(author, "동시 북마크");

        runConcurrently(8, () -> bookmarkService.bookmark(actor.getUsername(), post.getId()));

        User persistedUser = userRepository.findByUsername(actor.getUsername()).orElseThrow();
        Post persistedPost = postRepository.findById(post.getId()).orElseThrow();

        assertThat(bookmarkRepository.countByUserAndPost(persistedUser, persistedPost))
                .as("문제 해결 검증: 같은 북마크 요청이 동시에 와도 관계는 1개만 생성되어야 한다")
                .isEqualTo(1);
    }

    @Test
    void block_concurrentDuplicateRequests_createSingleRelation() throws Exception {
        User blocker = saveUser("blocker");
        User blocked = saveUser("blocked");

        runConcurrently(8, () -> blockService.block(blocker.getUsername(), blocked.getUsername()));

        User persistedBlocker = userRepository.findByUsername(blocker.getUsername()).orElseThrow();
        User persistedBlocked = userRepository.findByUsername(blocked.getUsername()).orElseThrow();

        assertThat(blockRepository.countByBlockerAndBlocked(persistedBlocker, persistedBlocked))
                .as("문제 해결 검증: 같은 차단 요청이 동시에 와도 관계는 1개만 생성되어야 한다")
                .isEqualTo(1);
    }

    private User saveUser(String username) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@test.com")
                .password(passwordEncoder.encode("pass123"))
                .build());
    }

    private Post savePost(User author, String title) {
        return postRepository.save(Post.builder()
                .title(title)
                .content("content")
                .author(author)
                .build());
    }

    private void runConcurrently(int workers, ThrowingRunnable action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
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
            future.get(10, TimeUnit.SECONDS);
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
