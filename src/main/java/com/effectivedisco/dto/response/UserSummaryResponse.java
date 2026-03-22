package com.effectivedisco.dto.response;

import com.effectivedisco.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 팔로워/팔로잉 목록, 차단 목록 등에서 사용하는 최소 사용자 정보 DTO.
 * 전체 프로필 대신 목록 표시에 필요한 필드만 담는다.
 */
@Getter
@Schema(description = "사용자 요약 정보 (목록 표시용)")
public class UserSummaryResponse {

    @Schema(description = "사용자명", example = "alice")
    private final String username;

    @Schema(description = "자기소개", example = "안녕하세요!", nullable = true)
    private final String bio;

    @Schema(description = "프로필 이미지 URL", nullable = true)
    private final String profileImageUrl;

    public UserSummaryResponse(User user) {
        this.username        = user.getUsername();
        this.bio             = user.getBio();
        this.profileImageUrl = user.getProfileImageUrl();
    }
}
