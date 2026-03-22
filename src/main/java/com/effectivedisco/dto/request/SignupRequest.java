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

    @Schema(description = "비밀번호 (6자 이상)", example = "secret123")
    @NotBlank
    @Size(min = 6)
    private String password;
}
