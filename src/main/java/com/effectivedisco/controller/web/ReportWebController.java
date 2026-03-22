package com.effectivedisco.controller.web;

import com.effectivedisco.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 게시물·댓글 신고 웹 컨트롤러.
 * 신고 처리 후 원래 페이지로 리다이렉트한다.
 */
@Controller
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportWebController {

    private final ReportService reportService;

    /** 게시물 신고 */
    @PostMapping("/posts/{postId}")
    public String reportPost(@PathVariable Long postId,
                             @RequestParam String reason,
                             @AuthenticationPrincipal UserDetails userDetails,
                             RedirectAttributes redirectAttributes) {
        try {
            reportService.reportPost(userDetails.getUsername(), postId, reason);
            redirectAttributes.addFlashAttribute("successMsg", "신고가 접수되었습니다.");
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMsg", e.getMessage());
        }
        return "redirect:/posts/" + postId;
    }

    /** 댓글 신고 */
    @PostMapping("/comments/{commentId}")
    public String reportComment(@PathVariable Long commentId,
                                @RequestParam String reason,
                                @RequestParam Long postId,
                                @AuthenticationPrincipal UserDetails userDetails,
                                RedirectAttributes redirectAttributes) {
        try {
            reportService.reportComment(userDetails.getUsername(), commentId, reason);
            redirectAttributes.addFlashAttribute("successMsg", "신고가 접수되었습니다.");
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMsg", e.getMessage());
        }
        return "redirect:/posts/" + postId + "#comment-" + commentId;
    }
}
