package com.effectivedisco.service;

import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.UserProfileResponse;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 사용자 관련 비즈니스 로직.
 * 현재는 프로필 조회를 담당한다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository     userRepository;
    private final PostRepository     postRepository;
    private final CommentRepository  commentRepository;
    private final PostLikeRepository postLikeRepository;

    /**
     * 사용자 프로필 정보를 조회한다.
     *
     * 반환 데이터:
     * - 기본 정보: username, 가입일
     * - 활동 통계: 게시물 수, 댓글 수, 받은 좋아요 수
     *
     * @param username 조회할 사용자명
     * @throws UsernameNotFoundException 존재하지 않는 사용자
     */
    public UserProfileResponse getProfile(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "존재하지 않는 사용자입니다: " + username));

        long postCount     = postRepository.countByAuthor(user);
        long commentCount  = commentRepository.countByAuthor(user);
        // 내가 작성한 게시물 전체에 달린 좋아요 합산
        long likesReceived = postLikeRepository.countLikesReceivedByUser(user);

        return new UserProfileResponse(user, postCount, commentCount, likesReceived);
    }
}
