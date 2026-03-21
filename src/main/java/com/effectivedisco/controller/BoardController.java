package com.effectivedisco.controller;

import com.effectivedisco.dto.request.BoardCreateRequest;
import com.effectivedisco.dto.response.BoardResponse;
import com.effectivedisco.service.BoardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 게시판 REST API 컨트롤러.
 *
 * GET /api/boards        - 전체 게시판 목록 조회 (인증 불필요)
 * POST /api/boards       - 게시판 생성 (인증 필요)
 */
@RestController
@RequestMapping("/api/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    /**
     * 전체 게시판 목록 반환.
     * 게시물 수도 함께 응답한다.
     */
    @GetMapping
    public ResponseEntity<List<BoardResponse>> getAllBoards() {
        return ResponseEntity.ok(boardService.getAllBoards());
    }

    /**
     * 새 게시판 생성.
     * slug 중복 시 400 Bad Request 응답 (GlobalExceptionHandler가 처리).
     */
    @PostMapping
    public ResponseEntity<BoardResponse> createBoard(@Valid @RequestBody BoardCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardService.createBoard(request));
    }
}
