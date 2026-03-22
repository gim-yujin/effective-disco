package com.effectivedisco.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Schema(description = "인증 응답 (회원가입·로그인 공통)")
public class AuthResponse {

    @Schema(description = "JWT Bearer 토큰", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    private String token;

    @Schema(description = "로그인된 사용자명", example = "alice")
    private String username;
}
