package com.effectivedisco.controller;

import com.effectivedisco.dto.request.BoardCreateRequest;
import com.effectivedisco.dto.response.BoardResponse;
import com.effectivedisco.service.BoardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Boards", description = "게시판 API")
@RestController
@RequestMapping("/api/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    @Operation(summary = "게시판 목록 조회", description = "전체 게시판 목록을 게시물 수와 함께 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "게시판 목록 조회 성공")
    })
    @GetMapping
    public ResponseEntity<List<BoardResponse>> getAllBoards() {
        return ResponseEntity.ok(boardService.getAllBoards());
    }

    @Operation(summary = "게시판 생성", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public ResponseEntity<BoardResponse> createBoard(@Valid @RequestBody BoardCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardService.createBoard(request));
    }
}
