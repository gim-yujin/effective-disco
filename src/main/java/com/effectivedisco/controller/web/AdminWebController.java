package com.effectivedisco.controller.web;

import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.BoardCreateRequest;
import com.effectivedisco.repository.UserRepository;
import com.effectivedisco.service.BoardService;
import com.effectivedisco.service.CommentService;
import com.effectivedisco.service.PostService;
import com.effectivedisco.service.ReportService;
import com.effectivedisco.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자 패널 웹 컨트롤러.
 * SecurityConfig에서 /admin/** 경로를 ROLE_ADMIN 만 접근 가능하도록 설정했으므로
 * 별도의 권한 체크 없이 비즈니스 로직에만 집중한다.
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminWebController {

    private final UserRepository userRepository;
    private final UserService    userService;
    private final PostService    postService;
    private final CommentService commentService;
    private final BoardService   boardService;
    private final ReportService  reportService;

    /* ── 대시보드 ────────────────────────────────────────────── */

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("boards", boardService.getAllBoards());
        model.addAttribute("users", userRepository.findAllByOrderByCreatedAtDesc());
        return "admin/index";
    }

    /* ── 사용자 관리 ─────────────────────────────────────────── */

    /**
     * 사용자 권한 토글: ROLE_USER ↔ ROLE_ADMIN.
     * 자기 자신의 권한은 변경할 수 없다.
     */
    @PostMapping("/users/{id}/toggle-role")
    public String toggleRole(@PathVariable Long id,
                             @RequestParam String currentUsername) {
        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + id));

        // 자기 자신의 권한은 변경 불가 — 관리자 잠금 방지
        if (!target.getUsername().equals(currentUsername)) {
            if (target.isAdmin()) {
                target.demoteToUser();
            } else {
                target.promoteToAdmin();
            }
            userRepository.save(target);
        }
        return "redirect:/admin";
    }

    /**
     * 계정 정지.
     * days = null 이면 영구 정지, days > 0 이면 그 일수만큼 기간 정지.
     * 관리자 자신의 계정은 정지할 수 없다.
     */
    @PostMapping("/users/{id}/suspend")
    public String suspendUser(@PathVariable Long id,
                              @RequestParam(required = false) String reason,
                              @RequestParam(required = false) Integer days,
                              @RequestParam String currentUsername) {
        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + id));
        // 관리자 자신은 정지 불가
        if (!target.getUsername().equals(currentUsername)) {
            userService.suspendUser(id, reason, days);
        }
        return "redirect:/admin";
    }

    /**
     * 계정 정지 해제.
     */
    @PostMapping("/users/{id}/unsuspend")
    public String unsuspendUser(@PathVariable Long id) {
        userService.unsuspendUser(id);
        return "redirect:/admin";
    }

    /* ── 게시물 관리 ─────────────────────────────────────────── */

    /** 관리자 강제 삭제 */
    @PostMapping("/posts/{id}/delete")
    public String deletePost(@PathVariable Long id) {
        postService.adminDeletePost(id);
        return "redirect:/admin";
    }

    /** 고정 핀 토글 */
    @PostMapping("/posts/{id}/pin")
    public String pinPost(@PathVariable Long id,
                          @RequestParam(required = false) String returnTo) {
        postService.adminPinToggle(id);
        return returnTo != null ? "redirect:" + returnTo : "redirect:/posts/" + id;
    }

    /* ── 댓글 관리 ───────────────────────────────────────────── */

    /** 관리자 강제 삭제: 작성자와 무관하게 댓글을 삭제한다. */
    @PostMapping("/comments/{id}/delete")
    public String deleteComment(@PathVariable Long id,
                                @RequestParam Long postId) {
        commentService.adminDeleteComment(id);
        return "redirect:/posts/" + postId;
    }

    /* ── 게시판 관리 ─────────────────────────────────────────── */

    @PostMapping("/boards")
    public String createBoard(@ModelAttribute BoardCreateRequest request) {
        boardService.createBoard(request);
        return "redirect:/admin";
    }

    @PostMapping("/boards/{slug}/update")
    public String updateBoard(@PathVariable String slug,
                              @ModelAttribute BoardCreateRequest request) {
        boardService.updateBoard(slug, request);
        return "redirect:/admin";
    }

    @PostMapping("/boards/{slug}/delete")
    public String deleteBoard(@PathVariable String slug) {
        boardService.deleteBoard(slug);
        return "redirect:/admin";
    }

    /* ── 신고 관리 ───────────────────────────────────────────── */

    /** 미처리 신고 목록 */
    @GetMapping("/reports")
    public String reports(Model model) {
        model.addAttribute("reports", reportService.getPendingReports());
        return "admin/reports";
    }

    /** 신고 처리: 조치 완료 */
    @PostMapping("/reports/{id}/resolve")
    public String resolveReport(@PathVariable Long id) {
        reportService.resolve(id);
        return "redirect:/admin/reports";
    }

    /** 신고 기각: 문제 없음 */
    @PostMapping("/reports/{id}/dismiss")
    public String dismissReport(@PathVariable Long id) {
        reportService.dismiss(id);
        return "redirect:/admin/reports";
    }
}
