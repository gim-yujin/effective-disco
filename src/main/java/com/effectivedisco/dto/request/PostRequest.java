package com.effectivedisco.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Schema(description = "게시물 작성/수정 요청")
public class PostRequest {

    @Schema(description = "게시물 제목 (최대 200자)", example = "Spring Boot 4 사용기")
    @NotBlank
    @Size(max = 200)
    private String title;

    /** 게시물 본문 — 최대 50,000자로 제한하여 과도한 페이로드를 차단한다. */
    @Schema(description = "게시물 본문 (최대 50,000자)", example = "Spring Boot 4를 사용해보니...")
    @NotBlank
    @Size(max = 50_000, message = "본문은 50,000자 이내로 입력하세요.")
    private String content;

    @Schema(description = "태그 목록 (쉼표 구분)", example = "spring, java, backend")
    private String tagsInput = "";

    /**
     * 게시물을 등록할 게시판의 슬러그.
     * 웹 폼에서는 hidden 필드로 전달되고,
     * REST API에서는 JSON 바디에 포함된다.
     * null이면 미분류(게시판 미지정) 게시물로 처리한다.
     */
    @Schema(description = "게시판 슬러그 (null이면 미분류)", example = "dev", nullable = true)
    private String boardSlug;

    /**
     * 첨부 이미지 서빙 URL 목록.
     * 웹 컨트롤러에서 ImageService.storeAll()로 파일들을 저장한 후 URL 목록을 설정한다.
     * REST API에서는 사용하지 않는다.
     */
    /**
     * 첨부 이미지 서빙 URL 목록.
     * 웹 컨트롤러에서 ImageService.storeAll()로 파일들을 저장한 후 URL 목록을 설정한다.
     * REST API에서는 사용하지 않는다.
     * 최대 10장으로 제한하여 과도한 이미지 첨부를 방지한다.
     */
    @Schema(description = "첨부 이미지 URL 목록 (REST API 미사용, 최대 10장)", hidden = true)
    @Size(max = 10, message = "이미지는 최대 10장까지 첨부할 수 있습니다.")
    private List<String> imageUrls = new ArrayList<>();

    /**
     * 초안 여부.
     * true이면 비공개 임시저장, false(기본값)이면 즉시 공개.
     * 웹 폼의 hidden 필드를 통해 "초안으로 저장" / "등록" 버튼으로 구분하여 전달된다.
     */
    @Schema(description = "초안 여부 (true: 임시저장, false: 공개)", example = "false")
    private boolean draft = false;
}
