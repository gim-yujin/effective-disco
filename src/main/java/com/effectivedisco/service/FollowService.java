package com.effectivedisco.service;

import com.effectivedisco.domain.Follow;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.dto.response.UserSummaryResponse;
import com.effectivedisco.repository.FollowRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 팔로우/팔로잉 기능 서비스.
 *
 * 주요 기능:
 * - 팔로우 토글 (팔로우/언팔로우)
 * - 팔로우 여부 조회
 * - 팔로워/팔로잉 수 조회
 * - 팔로우 피드 (팔로잉한 사용자의 최신 게시물 모음)
 */
@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowRepository   followRepository;
    private final UserRepository     userRepository;
    private final PostRepository     postRepository;

    /**
     * 팔로우를 토글한다.
     * 이미 팔로우 중이면 언팔로우(삭제), 아니면 팔로우(저장)한다.
     *
     * @param followerUsername  팔로우를 거는 사람 (현재 로그인 사용자)
     * @param followingUsername 팔로우 대상 (프로필 페이지의 사용자)
     * @return true = 팔로우 됨, false = 언팔로우 됨
     * @throws IllegalArgumentException 자기 자신을 팔로우하려 할 때
     */
    @Transactional
    public boolean toggle(String followerUsername, String followingUsername) {
        if (followerUsername.equals(followingUsername)) {
            throw new IllegalArgumentException("자기 자신을 팔로우할 수 없습니다.");
        }
        User follower  = findUser(followerUsername);
        User following = findUser(followingUsername);

        return followRepository.findByFollowerAndFollowing(follower, following)
                .map(follow -> {
                    // 이미 팔로우 중 → 언팔로우
                    followRepository.delete(follow);
                    return false;
                })
                .orElseGet(() -> {
                    // 팔로우 중 아님 → 팔로우
                    followRepository.save(new Follow(follower, following));
                    return true;
                });
    }

    /**
     * 특정 사용자가 다른 사용자를 팔로우하는지 확인한다.
     * 프로필 페이지의 팔로우/언팔로우 버튼 표시에 사용한다.
     */
    public boolean isFollowing(String followerUsername, String followingUsername) {
        User follower  = userRepository.findByUsername(followerUsername).orElse(null);
        User following = userRepository.findByUsername(followingUsername).orElse(null);
        if (follower == null || following == null) return false;
        return followRepository.existsByFollowerAndFollowing(follower, following);
    }

    /** 특정 사용자의 팔로워 수 (이 사람을 팔로우하는 사람 수) */
    public long getFollowerCount(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return 0;
        return followRepository.countByFollowing(user);
    }

    /** 특정 사용자의 팔로잉 수 (이 사람이 팔로우하는 사람 수) */
    public long getFollowingCount(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return 0;
        return followRepository.countByFollower(user);
    }

    /**
     * 특정 사용자를 팔로우하는 사람(팔로워) 목록을 반환한다.
     * 팔로워 목록 페이지에서 사용한다.
     *
     * @param username 팔로워 목록을 조회할 대상 사용자명
     * @return 팔로워 목록 (최신 팔로우 순)
     */
    public List<UserSummaryResponse> getFollowers(String username) {
        User user = findUser(username);
        return followRepository.findFollowers(user)
                .stream()
                .map(UserSummaryResponse::new)
                .toList();
    }

    /**
     * 특정 사용자가 팔로우하는 사람(팔로잉) 목록을 반환한다.
     * 팔로잉 목록 페이지에서 사용한다.
     *
     * @param username 팔로잉 목록을 조회할 사용자명
     * @return 팔로잉 목록 (최신 팔로우 순)
     */
    public List<UserSummaryResponse> getFollowings(String username) {
        User user = findUser(username);
        return followRepository.findFollowings(user)
                .stream()
                .map(UserSummaryResponse::new)
                .toList();
    }

    /**
     * 팔로우 피드: 현재 사용자가 팔로우하는 사람들의 최신 게시물을 페이징 반환한다.
     * 팔로잉 목록이 비어있으면 빈 페이지를 즉시 반환한다.
     * (JPQL IN 절에 빈 리스트를 넘기면 오류가 발생하므로 서비스에서 조기 반환한다.)
     *
     * @param username 피드를 요청한 사용자명
     */
    public Page<PostResponse> getFeed(String username, int page, int size) {
        User user = findUser(username);
        List<User> following = followRepository.findFollowingUsers(user);

        // 팔로잉한 사람이 없으면 빈 페이지 반환
        if (following.isEmpty()) {
            return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
        }

        return postRepository
                .findByAuthorInOrderByCreatedAtDesc(following, PageRequest.of(page, size))
                .map(PostResponse::new);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }
}
