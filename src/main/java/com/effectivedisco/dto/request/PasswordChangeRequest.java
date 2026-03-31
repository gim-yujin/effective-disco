package com.effectivedisco.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PasswordChangeRequest {

    @NotBlank(message = "현재 비밀번호를 입력하세요.")
    private String currentPassword;

    /** 새 비밀번호 — SignupRequest와 동일한 6~100자 제한을 적용한다. */
    @NotBlank(message = "새 비밀번호를 입력하세요.")
    @Size(min = 6, max = 100, message = "비밀번호는 6자 이상 100자 이하로 입력하세요.")
    private String newPassword;

    @NotBlank(message = "새 비밀번호 확인을 입력하세요.")
    private String confirmPassword;
}
