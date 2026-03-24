package com.effectivedisco.controller;

import com.effectivedisco.dto.request.CommentRequest;
import com.effectivedisco.dto.response.CommentResponse;
import com.effectivedisco.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * 댓글 REST API 컨트롤러.
 *
 * 모든 쓰기(POST/PUT/DELETE)는 JWT 인증 필요.
 * 읽기(GET)는 공개 엔드포인트.
 */
@Tag(name = "Comments", description = "댓글·대댓글 CRUD API")
@RestController
@RequestMapping("/api/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @Operation(summary = "댓글 목록 조회", description = "최상위 댓글 페이지를 반환하고, 각 댓글에는 1단계 대댓글이 포함됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "목록 조회 성공")
    })
    @GetMapping
    public ResponseEntity<Page<CommentResponse>> getComments(
            @Parameter(description = "게시물 ID") @PathVariable Long postId,
            @Parameter(description = "댓글 페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "댓글 페이지 크기", example = "50")
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(commentService.getCommentsPage(postId, page, size));
    }

    @Operation(summary = "댓글 작성", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "댓글 작성 성공"),
        @ApiResponse(responseCode = "400", description = "입력값 유효성 실패 또는 게시물 없음"),
        @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료")
    })
    @PostMapping
    public ResponseEntity<CommentResponse> createComment(
            @Parameter(description = "게시물 ID") @PathVariable Long postId,
            @Valid @RequestBody CommentRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.createComment(postId, request, userDetails.getUsername()));
    }

    @Operation(summary = "대댓글 작성", description = "최상위 댓글에만 1단계 답글을 달 수 있습니다. 대댓글에 답글 불가.",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "대댓글 작성 성공"),
        @ApiResponse(responseCode = "400", description = "댓글/게시물 없음 또는 대댓글에 답글 시도"),
        @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료")
    })
    @PostMapping("/{id}/replies")
    public ResponseEntity<CommentResponse> createReply(
            @Parameter(description = "게시물 ID") @PathVariable Long postId,
            @Parameter(description = "부모 댓글 ID") @PathVariable Long id,
            @Valid @RequestBody CommentRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.createReply(postId, id, request, userDetails.getUsername()));
    }

    @Operation(summary = "댓글 수정", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수정 성공"),
        @ApiResponse(responseCode = "400", description = "댓글/게시물 없음"),
        @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
        @ApiResponse(responseCode = "403", description = "본인 댓글이 아님")
    })
    @PutMapping("/{id}")
    public ResponseEntity<CommentResponse> updateComment(
            @Parameter(description = "게시물 ID") @PathVariable Long postId,
            @Parameter(description = "댓글 ID") @PathVariable Long id,
            @Valid @RequestBody CommentRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(commentService.updateComment(postId, id, request, userDetails.getUsername()));
    }

    @Operation(summary = "댓글 삭제", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 성공"),
        @ApiResponse(responseCode = "400", description = "댓글/게시물 없음"),
        @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
        @ApiResponse(responseCode = "403", description = "본인 댓글이 아님")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteComment(
            @Parameter(description = "게시물 ID") @PathVariable Long postId,
            @Parameter(description = "댓글 ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        commentService.deleteComment(postId, id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }
}
