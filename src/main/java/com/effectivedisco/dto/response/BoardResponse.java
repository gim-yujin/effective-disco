package com.effectivedisco.dto.response;

import com.effectivedisco.domain.Board;
import lombok.Getter;

/**
 * 게시판 응답 DTO.
 * API 응답 및 Thymeleaf 뷰 모델로 공용 사용한다.
 */
@Getter
public class BoardResponse {

    private final Long   id;
    private final String name;
    private final String slug;
    private final String description;

    /** 이 게시판에 속한 게시물 수 (홈 화면 게시판 목록에 표시) */
    private final long postCount;

    public BoardResponse(Board board, long postCount) {
        this.id          = board.getId();
        this.name        = board.getName();
        this.slug        = board.getSlug();
        this.description = board.getDescription();
        this.postCount   = postCount;
    }
}
