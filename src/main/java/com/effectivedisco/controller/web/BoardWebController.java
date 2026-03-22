package com.effectivedisco.controller.web;

import com.effectivedisco.dto.request.CommentRequest;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.BoardResponse;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.service.BlockService;
import com.effectivedisco.service.BoardService;
import com.effectivedisco.service.BookmarkService;
import com.effectivedisco.service.CommentService;
import com.effectivedisco.service.ImageService;
import com.effectivedisco.service.PostService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 웹(Thymeleaf) 컨트롤러.
 *
 * URL 구조:
 *   /                              홈 — 게시판 목록
 *   /boards/{slug}                 게시판별 게시물 목록
 *   /boards/{slug}/posts/new       특정 게시판에 글쓰기 폼
 *   /boards/{slug}/posts           글쓰기 폼 제출
 *   /posts/{id}                    게시물 상세
 *   /posts/{id}/edit               게시물 수정 폼
 *   /posts/{id}/delete             게시물 삭제
 *   /posts/{id}/like               좋아요 토글
 *   /posts/{postId}/comments/**    댓글·대댓글 CRUD
 */
@Controller
@RequiredArgsConstructor
public class BoardWebController {

    private final PostService     postService;
    private final BoardService    boardService;
    private final CommentService  commentService;
    private final BookmarkService bookmarkService;
    private final ImageService    imageService;
    private final BlockService    blockService;

    /* ══════════════════════════════════════════════════════════
     * 홈 / 게시판 목록
     * ══════════════════════════════════════════════════════════ */

    /**
     * 홈 화면 — 게시판 목록.
     * 각 게시판의 이름·설명·게시물 수를 표시한다.
     */
    @GetMapping("/")
    public String boardList(Model model) {
        model.addAttribute("boards", boardService.getAllBoards());
        // 홈 화면 인기 태그 (최대 15개)
        model.addAttribute("popularTags", postService.getPopularTagNames(15));
        return "boards/index";
    }

    /* ══════════════════════════════════════════════════════════
     * 게시판별 게시물 목록
     * ══════════════════════════════════════════════════════════ */

    /**
     * 특정 게시판의 게시물 목록.
     * keyword(검색)와 tag(태그 필터)를 조합해 사용할 수 있다.
     *
     * @param slug 게시판 슬러그 (URL 경로 변수)
     */
    @GetMapping("/boards/{slug}")
    public String boardPostList(@PathVariable String slug,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size,
                                @RequestParam(required = false) String keyword,
                                @RequestParam(required = false) String tag,
                                @RequestParam(defaultValue = "latest") String sort,
                                @AuthenticationPrincipal UserDetails userDetails,
                                Model model) {
        // sort 파라미터를 서비스에 전달해 좋아요순/댓글순 정렬 지원
        model.addAttribute("posts",       postService.getPosts(page, size, keyword, tag, slug, sort));
        model.addAttribute("pinnedPosts", postService.getPinnedPosts(slug));
        model.addAttribute("board",       boardService.getBoard(slug));
        model.addAttribute("keyword", keyword != null ? keyword : "");
        model.addAttribute("tag",     tag     != null ? tag     : "");
        model.addAttribute("sort",    sort);
        // 이 게시판 안에서 사용된 태그 목록 (태그 필터 바)
        model.addAttribute("allTags", postService.getAllTagNames());
        // 차단된 사용자명 집합 — 템플릿에서 해당 사용자의 게시물 행을 숨기는 데 사용
        Set<String> blocked = userDetails != null
                ? blockService.getBlockedUsernames(userDetails.getUsername())
                : Collections.emptySet();
        model.addAttribute("blockedUsernames", blocked);
        return "boards/list"; // templates/boards/list.html
    }

    /* ══════════════════════════════════════════════════════════
     * 게시물 작성 (게시판 지정)
     * ══════════════════════════════════════════════════════════ */

    /**
     * 특정 게시판에 새 게시물을 작성하는 폼.
     * boardSlug 가 hidden 필드로 PostRequest에 미리 채워진다.
     */
    @GetMapping("/boards/{slug}/posts/new")
    public String newPostForm(@PathVariable String slug, Model model) {
        PostRequest postRequest = new PostRequest();
        postRequest.setBoardSlug(slug); // 게시판 슬러그를 폼에 미리 설정
        model.addAttribute("postRequest", postRequest);
        model.addAttribute("board",  boardService.getBoard(slug));
        model.addAttribute("isEdit", false);
        return "post/form";
    }

    /**
     * 새 게시물 저장.
     * 폼에서 전달된 boardSlug 를 PostRequest 에서 읽어 서비스로 전달한다.
     * 저장 후 생성된 게시물 상세 페이지로 리다이렉트한다.
     */
    @PostMapping("/boards/{slug}/posts")
    public String createPost(@PathVariable String slug,
                             @ModelAttribute PostRequest postRequest,
                             // multiple 속성 덕분에 브라우저가 여러 파일을 리스트로 전송
                             @RequestParam(value = "images", required = false) List<MultipartFile> images,
                             @AuthenticationPrincipal UserDetails userDetails,
                             RedirectAttributes redirectAttributes) {
        postRequest.setBoardSlug(slug);
        try {
            // 유효 파일만 필터링하여 저장 후 URL 목록을 PostRequest에 설정
            postRequest.setImageUrls(imageService.storeAll(images));
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMsg", e.getMessage());
            return "redirect:/boards/" + slug + "/posts/new";
        }
        PostResponse post = postService.createPost(postRequest, userDetails.getUsername());
        return "redirect:/posts/" + post.getId();
    }

    /* ══════════════════════════════════════════════════════════
     * 게시물 상세
     * ══════════════════════════════════════════════════════════ */

    /**
     * 게시물 상세 페이지.
     *
     * 세션 기반 조회수 중복 방지:
     * HttpSession 의 "viewedPosts" Set에 포함되지 않은 게시물 ID만 조회수를 증가시킨다.
     * 같은 세션(= 브라우저 탭) 내에서 같은 게시물을 새로 고침해도 중복 카운트되지 않는다.
     */
    @GetMapping("/posts/{id}")
    public String postDetail(@PathVariable Long id,
                             Model model,
                             @AuthenticationPrincipal UserDetails userDetails,
                             HttpSession session) {
        // 세션에서 이미 조회한 게시물 ID Set을 가져오거나 새로 생성
        @SuppressWarnings("unchecked")
        Set<Long> viewed = (Set<Long>) session.getAttribute("viewedPosts");
        if (viewed == null) {
            viewed = new HashSet<>();
            session.setAttribute("viewedPosts", viewed);
        }
        // 처음 방문한 게시물이면 조회수를 1 증가
        if (viewed.add(id)) {
            postService.incrementViewCount(id);
        }

        PostResponse post = postService.getPost(id);

        // 초안은 작성자 본인만 열람 가능 — 다른 사용자가 URL로 직접 접근하면 홈으로 리다이렉트
        if (post.isDraft()) {
            boolean isAuthor = userDetails != null
                    && userDetails.getUsername().equals(post.getAuthor());
            if (!isAuthor) {
                return "redirect:/";
            }
        }

        model.addAttribute("post",           post);
        model.addAttribute("comments",       commentService.getComments(id));
        model.addAttribute("commentRequest", new CommentRequest());

        boolean liked      = userDetails != null && postService.isLikedByUser(id, userDetails.getUsername());
        boolean bookmarked = userDetails != null && bookmarkService.isBookmarked(userDetails.getUsername(), id);
        model.addAttribute("liked",      liked);
        model.addAttribute("bookmarked", bookmarked);

        // 차단된 사용자명 집합 — 댓글 목록에서 차단 사용자의 댓글을 숨기는 데 사용
        Set<String> blocked = userDetails != null
                ? blockService.getBlockedUsernames(userDetails.getUsername())
                : Collections.emptySet();
        model.addAttribute("blockedUsernames", blocked);

        return "post/detail";
    }

    /* ══════════════════════════════════════════════════════════
     * 게시물 수정·삭제·좋아요
     * ══════════════════════════════════════════════════════════ */

    /** 게시물 수정 폼 — 작성자 본인만 접근 가능 */
    @GetMapping("/posts/{id}/edit")
    public String editPostForm(@PathVariable Long id,
                               Model model,
                               @AuthenticationPrincipal UserDetails userDetails) {
        PostResponse post = postService.getPost(id);
        // 작성자가 아니면 상세 페이지로 리다이렉트
        if (!post.getAuthor().equals(userDetails.getUsername())) {
            return "redirect:/posts/" + id;
        }
        PostRequest postRequest = new PostRequest();
        postRequest.setTitle(post.getTitle());
        postRequest.setContent(post.getContent());
        postRequest.setTagsInput(String.join(", ", post.getTags()));
        postRequest.setBoardSlug(post.getBoardSlug()); // 게시판 정보 유지

        model.addAttribute("postRequest",    postRequest);
        model.addAttribute("postId",         id);
        model.addAttribute("board",          post.getBoardSlug() != null
                                             ? boardService.getBoard(post.getBoardSlug()) : null);
        model.addAttribute("isEdit",         true);
        // 수정 폼에서 기존 이미지 미리보기 표시용 — 새 파일 업로드 시 교체됨
        model.addAttribute("existingImages", post.getImageUrls());
        // 현재 초안 여부 — 버튼 레이블("발행" vs "저장") 결정에 사용
        model.addAttribute("isDraft",        post.isDraft());
        return "post/form";
    }

    /** 게시물 수정 저장 */
    @PostMapping("/posts/{id}/edit")
    public String updatePost(@PathVariable Long id,
                             @ModelAttribute PostRequest postRequest,
                             // multiple 속성 덕분에 브라우저가 여러 파일을 리스트로 전송
                             @RequestParam(value = "images", required = false) List<MultipartFile> images,
                             @AuthenticationPrincipal UserDetails userDetails,
                             RedirectAttributes redirectAttributes) {
        try {
            // 새 파일이 있으면 저장 후 PostRequest에 URL 목록 설정
            // 빈 목록이면 PostService에서 기존 이미지를 유지함
            postRequest.setImageUrls(imageService.storeAll(images));
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMsg", e.getMessage());
            return "redirect:/posts/" + id + "/edit";
        }
        postService.updatePost(id, postRequest, userDetails.getUsername());
        return "redirect:/posts/" + id;
    }

    /**
     * 게시물 삭제.
     * 삭제 후 해당 게시판 목록으로 돌아간다.
     * 게시판 미지정(boardSlug=null) 게시물은 홈("/")으로 이동한다.
     */
    @PostMapping("/posts/{id}/delete")
    public String deletePost(@PathVariable Long id,
                             @AuthenticationPrincipal UserDetails userDetails) {
        // 삭제 전에 게시판 슬러그를 미리 저장 (삭제 후엔 조회 불가)
        PostResponse post = postService.getPost(id);
        String boardSlug = post.getBoardSlug();

        postService.deletePost(id, userDetails.getUsername());

        // 게시판이 있으면 해당 게시판 목록으로, 없으면 홈으로
        return boardSlug != null
                ? "redirect:/boards/" + boardSlug
                : "redirect:/";
    }

    /** 좋아요 토글 */
    @PostMapping("/posts/{id}/like")
    public String toggleLike(@PathVariable Long id,
                             @AuthenticationPrincipal UserDetails userDetails) {
        postService.toggleLike(id, userDetails.getUsername());
        return "redirect:/posts/" + id;
    }

    /** 북마크 토글 */
    @PostMapping("/posts/{id}/bookmark")
    public String toggleBookmark(@PathVariable Long id,
                                 @AuthenticationPrincipal UserDetails userDetails) {
        bookmarkService.toggle(userDetails.getUsername(), id);
        return "redirect:/posts/" + id;
    }

    /* ══════════════════════════════════════════════════════════
     * 댓글 / 대댓글
     * ══════════════════════════════════════════════════════════ */

    /** 댓글 작성 */
    @PostMapping("/posts/{postId}/comments")
    public String addComment(@PathVariable Long postId,
                             @ModelAttribute CommentRequest commentRequest,
                             @AuthenticationPrincipal UserDetails userDetails) {
        commentService.createComment(postId, commentRequest, userDetails.getUsername());
        return "redirect:/posts/" + postId + "#comments";
    }

    /** 대댓글 작성 (댓글에 달리는 1단계 답글) */
    @PostMapping("/posts/{postId}/comments/{id}/replies")
    public String addReply(@PathVariable Long postId,
                           @PathVariable Long id,
                           @ModelAttribute CommentRequest commentRequest,
                           @AuthenticationPrincipal UserDetails userDetails) {
        commentService.createReply(postId, id, commentRequest, userDetails.getUsername());
        return "redirect:/posts/" + postId + "#comment-" + id;
    }

    /** 댓글·대댓글 삭제 */
    @PostMapping("/posts/{postId}/comments/{id}/delete")
    public String deleteComment(@PathVariable Long postId,
                                @PathVariable Long id,
                                @AuthenticationPrincipal UserDetails userDetails) {
        commentService.deleteComment(postId, id, userDetails.getUsername());
        return "redirect:/posts/" + postId + "#comments";
    }

    /* ══════════════════════════════════════════════════════════
     * 초안(Draft) 관리
     * ══════════════════════════════════════════════════════════ */

    /**
     * 내 초안 목록 페이지.
     * 로그인한 사용자 본인의 미공개 초안 게시물을 최신순으로 표시한다.
     */
    @GetMapping("/drafts")
    public String draftList(@AuthenticationPrincipal UserDetails userDetails,
                            @RequestParam(defaultValue = "0") int page,
                            Model model) {
        model.addAttribute("drafts", postService.getDrafts(userDetails.getUsername(), page, 20));
        return "post/drafts";
    }

    /**
     * 초안 발행 — draft=false로 전환 후 게시물 상세 페이지로 리다이렉트한다.
     * 작성자 본인만 발행할 수 있다 (PostService에서 권한 확인).
     */
    @PostMapping("/drafts/{id}/publish")
    public String publishDraft(@PathVariable Long id,
                               @AuthenticationPrincipal UserDetails userDetails) {
        postService.publishDraft(id, userDetails.getUsername());
        return "redirect:/posts/" + id;
    }
}
