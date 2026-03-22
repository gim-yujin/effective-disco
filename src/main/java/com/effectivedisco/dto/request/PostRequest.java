package com.effectivedisco.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * 첨부 이미지 서빙 URL 목록.
     * 웹 컨트롤러에서 ImageService.storeAll()로 파일들을 저장한 후 URL 목록을 설정한다.
     * REST API에서는 사용하지 않는다.
     */
    private List<String> imageUrls = new ArrayList<>();

    /**
     * 초안 여부.
     * true이면 비공개 임시저장, false(기본값)이면 즉시 공개.
     * 웹 폼의 hidden 필드를 통해 "초안으로 저장" / "등록" 버튼으로 구분하여 전달된다.
     */
    private boolean draft = false;
}
