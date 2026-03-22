package com.effectivedisco.controller.web;

import com.effectivedisco.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 비밀번호 재설정 웹 컨트롤러.
 *
 * URL:
 *   GET  /forgot-password          이메일 입력 폼
 *   POST /forgot-password          재설정 이메일 발송 요청
 *   GET  /reset-password?token=... 새 비밀번호 입력 폼
 *   POST /reset-password           비밀번호 변경 처리
 *
 * 모든 경로는 SecurityConfig 에서 permitAll 로 열려있어 비인증 사용자도 접근 가능하다.
 */
@Controller
@RequiredArgsConstructor
public class PasswordResetWebController {

    private final PasswordResetService passwordResetService;

    /* ── 이메일 입력 폼 ──────────────────────────────────────── */

    @GetMapping("/forgot-password")
    public String forgotPasswordForm() {
        return "auth/forgot-password";
    }

    /**
     * 비밀번호 재설정 이메일 발송 처리.
     * 보안을 위해 이메일 존재 여부와 무관하게 항상 성공 메시지를 표시한다.
     */
    @PostMapping("/forgot-password")
    public String requestReset(@RequestParam String email,
                               RedirectAttributes redirectAttributes) {
        passwordResetService.requestReset(email);
        // 이메일 존재 여부를 노출하지 않기 위해 항상 동일한 안내 메시지 출력
        redirectAttributes.addFlashAttribute("msg",
                "입력하신 이메일로 재설정 링크를 발송했습니다. 이메일을 확인해주세요.");
        return "redirect:/forgot-password";
    }

    /* ── 새 비밀번호 입력 폼 ─────────────────────────────────── */

    /**
     * 이메일 링크 클릭 시 진입하는 폼.
     * 토큰을 미리 폼에 hidden 값으로 넣어둔다.
     * 유효성은 제출 시에만 검증한다(GET 요청에서 토큰 소비 방지).
     */
    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "auth/reset-password";
    }

    /**
     * 비밀번호 재설정 처리.
     * 실패 시 동일 폼으로 돌아가 오류 메시지를 표시한다.
     */
    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token,
                                @RequestParam String newPassword,
                                @RequestParam String confirmPassword,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        try {
            passwordResetService.resetPassword(token, newPassword, confirmPassword);
            // 변경 성공 → 로그인 페이지로 이동 후 안내 메시지 표시
            redirectAttributes.addFlashAttribute("msg", "비밀번호가 변경되었습니다. 새 비밀번호로 로그인해주세요.");
            return "redirect:/login";
        } catch (IllegalArgumentException e) {
            // 토큰 오류 또는 비밀번호 불일치 — 같은 폼으로 돌아가 오류 표시
            model.addAttribute("token", token);
            model.addAttribute("errorMsg", e.getMessage());
            return "auth/reset-password";
        }
    }
}
