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

    @NotBlank
    private String content;
}
