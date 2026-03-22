package com.effectivedisco.dto.response;

import com.effectivedisco.domain.User;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 사용자 프로필 응답 DTO.
 * 기본 정보(username, 가입일)와 활동 통계를 담는다.
 */
@Getter
public class UserProfileResponse {

    private final String        username;
    private final String        email;
    private final String        bio;
    /** 가입 시각 (프로필 페이지에 "가입일" 로 표시) */
    private final LocalDateTime createdAt;

    /** 작성한 게시물 총 수 */
    private final long postCount;
    /** 작성한 댓글·대댓글 총 수 */
    private final long commentCount;
    /** 내 게시물에 달린 좋아요 총 수 */
    private final long likesReceived;

    public UserProfileResponse(User user, long postCount,
                               long commentCount, long likesReceived) {
        this.username      = user.getUsername();
        this.email         = user.getEmail();
        this.bio           = user.getBio();
        this.createdAt     = user.getCreatedAt();
        this.postCount     = postCount;
        this.commentCount  = commentCount;
        this.likesReceived = likesReceived;
    }
}
