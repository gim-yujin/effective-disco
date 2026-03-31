package com.effectivedisco.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageRequest {

    /** 수신자 username */
    @NotBlank
    private String to;

    @NotBlank
    @Size(max = 100)
    private String title;

    /** 쪽지 본문 — 최대 5,000자로 제한하여 과도한 페이로드를 차단한다. */
    @NotBlank
    @Size(max = 5_000, message = "쪽지 본문은 5,000자 이내로 입력하세요.")
    private String content;
}
