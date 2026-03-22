package com.effectivedisco.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "로그인 요청")
public class LoginRequest {

    @Schema(description = "사용자명", example = "alice")
    @NotBlank
    private String username;

    @Schema(description = "비밀번호", example = "secret123")
    @NotBlank
    private String password;
}
