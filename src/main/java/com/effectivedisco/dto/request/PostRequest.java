package com.effectivedisco.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PostRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    private String content;

    private String tagsInput = "";

    /**
     * 게시물을 등록할 게시판의 슬러그.
     * 웹 폼에서는 hidden 필드로 전달되고,
     * REST API에서는 JSON 바디에 포함된다.
     * null이면 미분류(게시판 미지정) 게시물로 처리한다.
     */
    private String boardSlug;
}
