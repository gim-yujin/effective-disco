package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.BookmarkRepository;
import com.effectivedisco.repository.FollowRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    @Autowired BoardRepository boardRepository;
    @Autowired TagRepository tagRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        postLikeRepository.deleteAll();
        bookmarkRepository.deleteAll();
        blockRepository.deleteAll();
        followRepository.deleteAll();
        notificationRepository.deleteAll();
        postRepository.deleteAll();
        tagRepository.deleteAll();
        boardRepository.deleteAll();
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
    void likeAndUnlike_concurrentOppositeRequests_keepRelationAndCounterConsistent() throws Exception {
        User author = saveUser("author");
        User actor  = saveUser("actor");
        Post post   = savePost(author, "좋아요/해제 경쟁");

        runConcurrently(List.of(
                () -> postService.likePost(post.getId(), actor.getUsername()),
                () -> postService.likePost(post.getId(), actor.getUsername()),
                () -> postService.likePost(post.getId(), actor.getUsername()),
                () -> postService.likePost(post.getId(), actor.getUsername()),
                () -> postService.unlikePost(post.getId(), actor.getUsername()),
                () -> postService.unlikePost(post.getId(), actor.getUsername()),
                () -> postService.unlikePost(post.getId(), actor.getUsername()),
                () -> postService.unlikePost(post.getId(), actor.getUsername())
        ));

        Post persistedPost = postRepository.findById(post.getId()).orElseThrow();
        User persistedUser = userRepository.findByUsername(actor.getUsername()).orElseThrow();
        long relationCount = postLikeRepository.countByPostAndUser(persistedPost, persistedUser);
        long likeCount = postRepository.findLikeCountById(post.getId());

        assertThat(relationCount)
                .as("문제 해결 검증: 좋아요/해제 요청이 섞여도 사용자-게시물 관계 row는 0개 또는 1개만 남아야 한다")
                .isBetween(0L, 1L);
        assertThat(likeCount)
                .as("문제 해결 검증: likeCount는 최종 좋아요 row 수와 항상 같아야 한다")
                .isEqualTo(relationCount);
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
    void followAndUnfollow_concurrentOppositeRequests_keepSingleRelationOrNone() throws Exception {
        User follower  = saveUser("follower");
        User following = saveUser("following");

        runConcurrently(List.of(
                () -> followService.follow(follower.getUsername(), following.getUsername()),
                () -> followService.follow(follower.getUsername(), following.getUsername()),
                () -> followService.follow(follower.getUsername(), following.getUsername()),
                () -> followService.follow(follower.getUsername(), following.getUsername()),
                () -> followService.unfollow(follower.getUsername(), following.getUsername()),
                () -> followService.unfollow(follower.getUsername(), following.getUsername()),
                () -> followService.unfollow(follower.getUsername(), following.getUsername()),
                () -> followService.unfollow(follower.getUsername(), following.getUsername())
        ));

        User persistedFollower  = userRepository.findByUsername(follower.getUsername()).orElseThrow();
        User persistedFollowing = userRepository.findByUsername(following.getUsername()).orElseThrow();

        assertThat(followRepository.countByFollowerAndFollowing(persistedFollower, persistedFollowing))
                .as("문제 해결 검증: 팔로우/언팔로우 경쟁 후에도 관계 row는 0개 또는 1개만 남아야 한다")
                .isBetween(0L, 1L);
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
    void bookmarkAndUnbookmark_concurrentOppositeRequests_keepSingleRelationOrNone() throws Exception {
        User author = saveUser("author");
        User actor  = saveUser("actor");
        Post post   = savePost(author, "북마크/해제 경쟁");

        runConcurrently(List.of(
                () -> bookmarkService.bookmark(actor.getUsername(), post.getId()),
                () -> bookmarkService.bookmark(actor.getUsername(), post.getId()),
                () -> bookmarkService.bookmark(actor.getUsername(), post.getId()),
                () -> bookmarkService.bookmark(actor.getUsername(), post.getId()),
                () -> bookmarkService.unbookmark(actor.getUsername(), post.getId()),
                () -> bookmarkService.unbookmark(actor.getUsername(), post.getId()),
                () -> bookmarkService.unbookmark(actor.getUsername(), post.getId()),
                () -> bookmarkService.unbookmark(actor.getUsername(), post.getId())
        ));

        User persistedUser = userRepository.findByUsername(actor.getUsername()).orElseThrow();
        Post persistedPost = postRepository.findById(post.getId()).orElseThrow();

        assertThat(bookmarkRepository.countByUserAndPost(persistedUser, persistedPost))
                .as("문제 해결 검증: 북마크/해제 경쟁 후에도 관계 row는 0개 또는 1개만 남아야 한다")
                .isBetween(0L, 1L);
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

    @Test
    void blockAndUnblock_concurrentOppositeRequests_keepSingleRelationOrNone() throws Exception {
        User blocker = saveUser("blocker");
        User blocked = saveUser("blocked");

        runConcurrently(List.of(
                () -> blockService.block(blocker.getUsername(), blocked.getUsername()),
                () -> blockService.block(blocker.getUsername(), blocked.getUsername()),
                () -> blockService.block(blocker.getUsername(), blocked.getUsername()),
                () -> blockService.block(blocker.getUsername(), blocked.getUsername()),
                () -> blockService.unblock(blocker.getUsername(), blocked.getUsername()),
                () -> blockService.unblock(blocker.getUsername(), blocked.getUsername()),
                () -> blockService.unblock(blocker.getUsername(), blocked.getUsername()),
                () -> blockService.unblock(blocker.getUsername(), blocked.getUsername())
        ));

        User persistedBlocker = userRepository.findByUsername(blocker.getUsername()).orElseThrow();
        User persistedBlocked = userRepository.findByUsername(blocked.getUsername()).orElseThrow();

        assertThat(blockRepository.countByBlockerAndBlocked(persistedBlocker, persistedBlocked))
                .as("문제 해결 검증: 차단/해제 경쟁 후에도 관계 row는 0개 또는 1개만 남아야 한다")
                .isBetween(0L, 1L);
    }

    @Test
    void createPost_concurrentSameNewTag_createsSingleTagAndSucceeds() throws Exception {
        User authorA = saveUser("authorA");
        User authorB = saveUser("authorB");
        Board board = saveBoard("write-concurrency-board");
        String tagName = "write-race-tag";

        PostRequest requestA = new PostRequest();
        requestA.setTitle("race-a");
        requestA.setContent("content-a");
        requestA.setBoardSlug(board.getSlug());
        requestA.setTagsInput("loadtest," + tagName);

        PostRequest requestB = new PostRequest();
        requestB.setTitle("race-b");
        requestB.setContent("content-b");
        requestB.setBoardSlug(board.getSlug());
        requestB.setTagsInput("loadtest," + tagName);

        runConcurrently(List.of(
                () -> postService.createPost(requestA, authorA.getUsername()),
                () -> postService.createPost(requestB, authorB.getUsername())
        ));

        assertThat(tagRepository.findAllByNameIn(Set.of(tagName)))
                .as("문제 해결 검증: 같은 새 태그를 동시에 포함한 게시물 작성이 와도 tags.name row는 1개만 남아야 한다")
                .hasSize(1);
        assertThat(postRepository.count())
                .as("문제 해결 검증: 태그 생성 race가 나도 게시물 작성 자체는 둘 다 성공해야 한다")
                .isEqualTo(2);
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

    private Board saveBoard(String slug) {
        return boardRepository.save(Board.builder()
                .name("Board " + slug)
                .slug(slug)
                .description("test board")
                .build());
    }

    private void runConcurrently(int workers, ThrowingRunnable action) throws Exception {
        List<ThrowingRunnable> actions = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            actions.add(action);
        }
        runConcurrently(actions);
    }

    private void runConcurrently(List<ThrowingRunnable> actions) throws Exception {
        int workers = actions.size();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
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
