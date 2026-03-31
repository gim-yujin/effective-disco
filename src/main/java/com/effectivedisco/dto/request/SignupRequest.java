package com.effectivedisco.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "회원가입 요청")
public class SignupRequest {

    @Schema(description = "사용자명 (3~20자, 영문·숫자·밑줄)", example = "alice")
    @NotBlank
    @Size(min = 3, max = 20)
    private String username;

    @Schema(description = "이메일 주소", example = "alice@example.com")
    @NotBlank
    @Email
    private String email;

    /**
     * 비밀번호 — 6~100자 제한.
     * 최솟값: 보안상 너무 짧은 비밀번호 차단.
     * 최댓값: BCrypt 해싱 시 과도한 CPU 소모를 방지 (72바이트 이상은 BCrypt가 무시하지만, 입력 자체를 제한).
     */
    @Schema(description = "비밀번호 (6~100자)", example = "secret123")
    @NotBlank
    @Size(min = 6, max = 100, message = "비밀번호는 6자 이상 100자 이하로 입력하세요.")
    private String password;
}
