package com.effectivedisco.controller;

import com.effectivedisco.domain.BookmarkFolder;
import com.effectivedisco.dto.request.BookmarkFolderNameRequest;
import com.effectivedisco.dto.request.BookmarkMoveRequest;
import com.effectivedisco.dto.response.BookmarkFolderResponse;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.service.BookmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 북마크 및 북마크 폴더 REST API 컨트롤러.
 *
 * POST   /api/bookmarks/{postId}            → 북마크 등록
 * DELETE /api/bookmarks/{postId}            → 북마크 해제
 * GET    /api/bookmarks                     → 전체 북마크 목록
 * GET    /api/bookmarks?folderId={id}       → 폴더별 북마크 목록
 * PUT    /api/bookmarks/{postId}/folder     → 북마크 폴더 이동
 * GET    /api/bookmark-folders              → 폴더 목록
 * POST   /api/bookmark-folders              → 폴더 생성
 * PUT    /api/bookmark-folders/{id}         → 폴더 이름 변경
 * DELETE /api/bookmark-folders/{id}         → 폴더 삭제
 */
@Tag(name = "Bookmarks", description = "북마크 및 폴더 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    /* ── 북마크 CRUD ─────────────────────────────────────────── */

    @Operation(summary = "북마크 등록", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/bookmarks/{postId}")
    public ResponseEntity<Void> bookmark(@PathVariable Long postId,
                                          @AuthenticationPrincipal UserDetails userDetails) {
        bookmarkService.bookmark(userDetails.getUsername(), postId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "북마크 해제", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/bookmarks/{postId}")
    public ResponseEntity<Void> unbookmark(@PathVariable Long postId,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        bookmarkService.unbookmark(userDetails.getUsername(), postId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "북마크 목록 조회", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/bookmarks")
    public ResponseEntity<List<PostResponse>> getBookmarks(
            @RequestParam(required = false) Long folderId,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails.getUsername();
        List<PostResponse> bookmarks = folderId != null
                ? bookmarkService.getBookmarksByFolder(username, folderId)
                : bookmarkService.getBookmarks(username);
        return ResponseEntity.ok(bookmarks);
    }

    @Operation(summary = "북마크 폴더 이동", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/bookmarks/{postId}/folder")
    public ResponseEntity<Void> moveBookmark(@PathVariable Long postId,
                                              @Valid @RequestBody BookmarkMoveRequest request,
                                              @AuthenticationPrincipal UserDetails userDetails) {
        bookmarkService.moveBookmarkToFolder(userDetails.getUsername(), postId, request.getFolderId());
        return ResponseEntity.ok().build();
    }

    /* ── 폴더 CRUD ───────────────────────────────────────────── */

    @Operation(summary = "북마크 폴더 목록", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/bookmark-folders")
    public ResponseEntity<List<BookmarkFolderResponse>> getFolders(
            @AuthenticationPrincipal UserDetails userDetails) {
        List<BookmarkFolderResponse> folders = bookmarkService.getFolders(userDetails.getUsername())
                .stream().map(BookmarkFolderResponse::new).toList();
        return ResponseEntity.ok(folders);
    }

    @Operation(summary = "북마크 폴더 생성", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/bookmark-folders")
    public ResponseEntity<BookmarkFolderResponse> createFolder(
            @Valid @RequestBody BookmarkFolderNameRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        BookmarkFolder folder = bookmarkService.createFolder(userDetails.getUsername(), request.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(new BookmarkFolderResponse(folder));
    }

    @Operation(summary = "북마크 폴더 이름 변경", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/bookmark-folders/{id}")
    public ResponseEntity<BookmarkFolderResponse> renameFolder(
            @PathVariable Long id,
            @Valid @RequestBody BookmarkFolderNameRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        BookmarkFolder folder = bookmarkService.renameFolder(userDetails.getUsername(), id, request.getName());
        return ResponseEntity.ok(new BookmarkFolderResponse(folder));
    }

    @Operation(summary = "북마크 폴더 삭제", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/bookmark-folders/{id}")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        bookmarkService.deleteFolder(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }
}
