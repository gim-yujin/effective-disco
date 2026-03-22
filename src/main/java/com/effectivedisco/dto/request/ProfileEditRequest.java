package com.effectivedisco.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProfileEditRequest {

    @Email(message = "올바른 이메일 형식이 아닙니다.")
    @Size(max = 100, message = "이메일은 100자 이내로 입력하세요.")
    private String email;

    @Size(max = 300, message = "자기소개는 300자 이내로 입력하세요.")
    private String bio;
}
