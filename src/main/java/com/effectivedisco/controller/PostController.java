package com.effectivedisco.controller;

import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.LikeResponse;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.service.PostService;
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

@Tag(name = "Posts", description = "게시물 CRUD 및 좋아요 API")
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @Operation(summary = "게시물 목록 조회",
               description = "boardSlug·keyword·tag 조합으로 필터링 가능합니다. " +
                             "sort: latest(최신순, 기본) | likes(좋아요순) | comments(댓글순)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "목록 조회 성공")
    })
    @GetMapping
    public ResponseEntity<Page<PostResponse>> getPosts(
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "10")
            @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "키워드 검색어")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "태그 이름으로 필터링")
            @RequestParam(required = false) String tag,
            @Parameter(description = "게시판 슬러그로 필터링 (예: free, dev)")
            @RequestParam(required = false) String boardSlug,
            @Parameter(description = "정렬 기준: latest | likes | comments")
            @RequestParam(defaultValue = "latest") String sort) {
        return ResponseEntity.ok(postService.getPosts(page, size, keyword, tag, boardSlug, sort));
    }

    @Operation(summary = "게시물 단건 조회")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "조회 성공"),
        @ApiResponse(responseCode = "400", description = "게시물을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<PostResponse> getPost(
            @Parameter(description = "게시물 ID") @PathVariable Long id) {
        return ResponseEntity.ok(postService.getPost(id));
    }

    @Operation(summary = "게시물 작성", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "게시물 작성 성공"),
        @ApiResponse(responseCode = "400", description = "입력값 유효성 실패"),
        @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료")
    })
    @PostMapping
    public ResponseEntity<PostResponse> createPost(
            @Valid @RequestBody PostRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(postService.createPost(request, userDetails.getUsername()));
    }

    @Operation(summary = "게시물 수정", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "수정 성공"),
        @ApiResponse(responseCode = "400", description = "입력값 유효성 실패 또는 게시물 없음"),
        @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
        @ApiResponse(responseCode = "403", description = "본인 게시물이 아님")
    })
    @PutMapping("/{id}")
    public ResponseEntity<PostResponse> updatePost(
            @Parameter(description = "게시물 ID") @PathVariable Long id,
            @Valid @RequestBody PostRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(postService.updatePost(id, request, userDetails.getUsername()));
    }

    @Operation(summary = "게시물 삭제", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "삭제 성공"),
        @ApiResponse(responseCode = "400", description = "게시물을 찾을 수 없음"),
        @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료"),
        @ApiResponse(responseCode = "403", description = "본인 게시물이 아님")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePost(
            @Parameter(description = "게시물 ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        postService.deletePost(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "좋아요 토글", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "좋아요/취소 성공"),
        @ApiResponse(responseCode = "400", description = "게시물을 찾을 수 없음"),
        @ApiResponse(responseCode = "401", description = "인증 토큰 없음 또는 만료")
    })
    @PostMapping("/{id}/like")
    public ResponseEntity<LikeResponse> toggleLike(
            @Parameter(description = "게시물 ID") @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(postService.toggleLike(id, userDetails.getUsername()));
    }
}
