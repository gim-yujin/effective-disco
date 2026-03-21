package com.effectivedisco.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 게시판 생성 REST API 요청 DTO.
 * slug는 URL-safe 형식 (소문자·숫자·하이픈)만 허용한다.
 */
@Getter
@Setter
public class BoardCreateRequest {

    /** 게시판 표시 이름 */
    @NotBlank
    @Size(max = 50)
    private String name;

    /**
     * URL 슬러그.
     * 예: "free", "tech-talk", "qna"
     */
    @NotBlank
    @Size(max = 30)
    @Pattern(regexp = "^[a-z0-9-]+$", message = "슬러그는 소문자·숫자·하이픈만 사용 가능합니다")
    private String slug;

    /** 게시판 설명 (선택) */
    @Size(max = 200)
    private String description;
}
