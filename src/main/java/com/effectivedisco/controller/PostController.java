package com.effectivedisco.controller;

import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.LikeResponse;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
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
    @GetMapping
    public ResponseEntity<Page<PostResponse>> getPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String boardSlug,
            @RequestParam(defaultValue = "latest") String sort) {
        return ResponseEntity.ok(postService.getPosts(page, size, keyword, tag, boardSlug, sort));
    }

    @Operation(summary = "게시물 단건 조회")
    @GetMapping("/{id}")
    public ResponseEntity<PostResponse> getPost(@PathVariable Long id) {
        return ResponseEntity.ok(postService.getPost(id));
    }

    @Operation(summary = "게시물 작성", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public ResponseEntity<PostResponse> createPost(
            @Valid @RequestBody PostRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(postService.createPost(request, userDetails.getUsername()));
    }

    @Operation(summary = "게시물 수정", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/{id}")
    public ResponseEntity<PostResponse> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody PostRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(postService.updatePost(id, request, userDetails.getUsername()));
    }

    @Operation(summary = "게시물 삭제", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePost(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        postService.deletePost(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "좋아요 토글", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{id}/like")
    public ResponseEntity<LikeResponse> toggleLike(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(postService.toggleLike(id, userDetails.getUsername()));
    }
}
