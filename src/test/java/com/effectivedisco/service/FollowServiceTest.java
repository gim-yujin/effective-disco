package com.effectivedisco.service;

import com.effectivedisco.domain.Follow;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.FollowRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.RelationAtomicInserter;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock FollowRepository        followRepository;
    @Mock UserRepository          userRepository;
    @Mock PostRepository          postRepository;
    @Mock UserLookupService       userLookupService;
    @Mock RelationAtomicInserter  relationAtomicInserter;

    @InjectMocks FollowService followService;

    // ── follow / unfollow ─────────────────────────────────

    @Test
    void follow_notFollowing_insertsAtomically() {
        User follower  = makeUser("alice");
        User following = makeUser("bob");

        given(userLookupService.findByUsername("alice")).willReturn(follower);
        given(userLookupService.findByUsername("bob")).willReturn(following);

        followService.follow("alice", "bob");

        verify(relationAtomicInserter).insertFollow(eq(follower.getId()), eq(following.getId()), any(LocalDateTime.class));
        verify(followRepository, never()).save(any());
    }

    @Test
    void follow_alreadyFollowing_isIdempotent() {
        User follower  = makeUser("alice");
        User following = makeUser("bob");

        given(userLookupService.findByUsername("alice")).willReturn(follower);
        given(userLookupService.findByUsername("bob")).willReturn(following);
        given(relationAtomicInserter.insertFollow(any(), any(), any(LocalDateTime.class))).willReturn(0);

        followService.follow("alice", "bob");

        verify(relationAtomicInserter).insertFollow(eq(follower.getId()), eq(following.getId()), any(LocalDateTime.class));
        verify(followRepository, never()).save(any());
    }

    @Test
    void follow_selfFollow_throwsIllegalArgumentException() {
        // 자기 자신을 팔로우하려 할 때 예외 발생
        assertThatThrownBy(() -> followService.follow("alice", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 자신을 팔로우할 수 없습니다");

        verify(relationAtomicInserter, never()).insertFollow(any(), any(), any(LocalDateTime.class));
        verify(followRepository, never()).save(any());
    }

    @Test
    void unfollow_existingFollow_deletesRelation() {
        User follower  = makeUser("alice");
        User following = makeUser("bob");

        given(userLookupService.findByUsername("alice")).willReturn(follower);
        given(userLookupService.findByUsername("bob")).willReturn(following);
        given(followRepository.deleteByFollowerAndFollowing(follower, following)).willReturn(1L);

        followService.unfollow("alice", "bob");

        verify(followRepository).deleteByFollowerAndFollowing(follower, following);
    }

    @Test
    void unfollow_missingFollow_isIdempotent() {
        User follower  = makeUser("alice");
        User following = makeUser("bob");

        given(userLookupService.findByUsername("alice")).willReturn(follower);
        given(userLookupService.findByUsername("bob")).willReturn(following);
        given(followRepository.deleteByFollowerAndFollowing(follower, following)).willReturn(0L);

        followService.unfollow("alice", "bob");

        verify(followRepository).deleteByFollowerAndFollowing(follower, following);
    }

    // ── isFollowing ───────────────────────────────────────

    @Test
    void isFollowing_follows_returnsTrue() {
        User follower  = makeUser("alice");
        User following = makeUser("bob");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(follower));
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(following));
        given(followRepository.existsByFollowerAndFollowing(follower, following)).willReturn(true);

        assertThat(followService.isFollowing("alice", "bob")).isTrue();
    }

    @Test
    void isFollowing_notFollowing_returnsFalse() {
        User follower  = makeUser("alice");
        User following = makeUser("bob");

        given(userRepository.findByUsername("alice")).willReturn(Optional.of(follower));
        given(userRepository.findByUsername("bob")).willReturn(Optional.of(following));
        given(followRepository.existsByFollowerAndFollowing(follower, following)).willReturn(false);

        assertThat(followService.isFollowing("alice", "bob")).isFalse();
    }

    @Test
    void isFollowing_unknownUser_returnsFalse() {
        // 사용자가 없으면 null을 반환하고 false 처리
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThat(followService.isFollowing("ghost", "bob")).isFalse();
    }

    // ── getFeed ───────────────────────────────────────────

    @Test
    void getFeed_withFollowing_returnsMappedPage() {
        User alice = makeUser("alice");
        User bob   = makeUser("bob");
        Post post  = makePost(1L, "Hello", "Content", bob);

        given(userLookupService.findByUsername("alice")).willReturn(alice);
        given(followRepository.findFollowingUsers(alice)).willReturn(List.of(bob));
        // 팔로우한 사용자의 공개 게시물 페이지 반환
        given(postRepository.findByAuthorInOrderByCreatedAtDesc(
                List.of(bob), PageRequest.of(0, 10)))
                .willReturn(new PageImpl<>(List.of(post)));

        Page<PostResponse> result = followService.getFeed("alice", 0, 10);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("Hello");
        assertThat(result.getContent().get(0).getLikeCount()).isEqualTo(0L);
    }

    @Test
    void getFeed_emptyFollowing_returnsEmptyPageWithoutQueryingPostRepository() {
        User alice = makeUser("alice");

        given(userLookupService.findByUsername("alice")).willReturn(alice);
        // 팔로우한 사람이 없으면 postRepository 쿼리 없이 빈 페이지 반환
        given(followRepository.findFollowingUsers(alice)).willReturn(List.of());

        Page<PostResponse> result = followService.getFeed("alice", 0, 10);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        // JPQL IN 절 오류 방지를 위해 postRepository를 전혀 호출하지 않아야 한다
        verify(postRepository, never()).findByAuthorInOrderByCreatedAtDesc(any(), any());
    }

    // ── helpers ───────────────────────────────────────────

    private User makeUser(String username) {
        return User.builder()
                .username(username)
                .email(username + "@test.com")
                .password("encoded")
                .build();
    }

    private Post makePost(Long id, String title, String content, User author) {
        Post post = Post.builder().title(title).content(content).author(author).build();
        ReflectionTestUtils.setField(post, "id", id);
        return post;
    }
}
