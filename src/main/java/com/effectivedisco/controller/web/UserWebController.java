package com.effectivedisco.controller.web;

import com.effectivedisco.dto.request.PasswordChangeRequest;
import com.effectivedisco.dto.request.ProfileEditRequest;
import com.effectivedisco.service.BookmarkService;
import com.effectivedisco.service.PostService;
import com.effectivedisco.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 사용자 프로필·설정 웹 컨트롤러.
 *
 * GET  /users/{username}   → 공개 프로필 페이지
 * GET  /settings           → 내 설정 페이지 (로그인 필요)
 * POST /settings/profile   → bio·이메일 변경
 * POST /settings/password  → 비밀번호 변경
 * POST /settings/withdraw  → 회원 탈퇴
 */
@Controller
@RequiredArgsConstructor
public class UserWebController {

    private final UserService     userService;
    private final PostService     postService;
    private final BookmarkService bookmarkService;

    /* ── 공개 프로필 ──────────────────────────────────────────── */

    @GetMapping("/bookmarks")
    public String bookmarks(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        model.addAttribute("bookmarks", bookmarkService.getBookmarks(userDetails.getUsername()));
        return "users/bookmarks";
    }

    @GetMapping("/users/{username}")
    public String profile(@PathVariable String username,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        model.addAttribute("profile", userService.getProfile(username));
        model.addAttribute("posts", postService.getPostsByAuthor(username, page, 10));
        return "users/profile";
    }

    /* ── 설정 페이지 ──────────────────────────────────────────── */

    @GetMapping("/settings")
    public String settings(@AuthenticationPrincipal UserDetails userDetails,
                           Model model) {
        model.addAttribute("profile", userService.getProfile(userDetails.getUsername()));
        return "users/settings";
    }

    /* ── 프로필(bio·이메일) 변경 ─────────────────────────────── */

    @PostMapping("/settings/profile")
    public String updateProfile(@AuthenticationPrincipal UserDetails userDetails,
                                @ModelAttribute ProfileEditRequest req,
                                RedirectAttributes redirectAttributes) {
        try {
            userService.updateProfile(userDetails.getUsername(), req);
            redirectAttributes.addFlashAttribute("profileMsg", "프로필이 수정되었습니다.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("profileError", e.getMessage());
        }
        return "redirect:/settings";
    }

    /* ── 비밀번호 변경 ─────────────────────────────────────────── */

    @PostMapping("/settings/password")
    public String changePassword(@AuthenticationPrincipal UserDetails userDetails,
                                 @ModelAttribute PasswordChangeRequest req,
                                 RedirectAttributes redirectAttributes) {
        try {
            userService.changePassword(userDetails.getUsername(), req);
            redirectAttributes.addFlashAttribute("passwordMsg", "비밀번호가 변경되었습니다.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("passwordError", e.getMessage());
        }
        return "redirect:/settings";
    }

    /* ── 회원 탈퇴 ─────────────────────────────────────────────── */

    @PostMapping("/settings/withdraw")
    public String withdraw(@AuthenticationPrincipal UserDetails userDetails,
                           @RequestParam String password,
                           HttpServletRequest request,
                           RedirectAttributes redirectAttributes) {
        try {
            userService.withdraw(userDetails.getUsername(), password);
            // 세션 무효화 → 로그아웃 처리
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            return "redirect:/login?withdrawn";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("withdrawError", e.getMessage());
            return "redirect:/settings";
        }
    }
}
