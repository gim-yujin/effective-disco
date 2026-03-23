package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Board;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 게시판 응답 DTO.
 * API 응답 및 Thymeleaf 뷰 모델로 공용 사용한다.
 */
@Schema(description = "게시판 응답 DTO")
@Getter
public class BoardResponse {

    @Schema(description = "게시판 고유 ID", example = "1")
    private final Long   id;

    @Schema(description = "게시판 이름", example = "자유게시판")
    private final String name;

    @Schema(description = "게시판 슬러그 (URL 경로 식별자)", example = "free")
    private final String slug;

    @Schema(description = "게시판 설명", example = "자유롭게 이야기를 나누는 공간입니다.")
    private final String description;

    /** 이 게시판에 속한 게시물 수 (홈 화면 게시판 목록에 표시) */
    @Schema(description = "이 게시판에 속한 게시물 수", example = "42")
    private final long postCount;

    public BoardResponse(Board board, long postCount) {
        this.id          = board.getId();
        this.name        = board.getName();
        this.slug        = board.getSlug();
        this.description = board.getDescription();
        this.postCount   = postCount;
    }
}
