package com.effectivedisco.controller.web;

import com.effectivedisco.dto.request.MessageRequest;
import com.effectivedisco.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/messages")
@RequiredArgsConstructor
public class MessageWebController {

    private final MessageService messageService;

    /* ── 받은 편지함 ─────────────────────────────────────────── */

    @GetMapping
    public String inbox(@AuthenticationPrincipal UserDetails user, Model model) {
        model.addAttribute("messages", messageService.getInbox(user.getUsername()));
        model.addAttribute("box", "inbox");
        return "messages/inbox";
    }

    /* ── 보낸 편지함 ─────────────────────────────────────────── */

    @GetMapping("/sent")
    public String sent(@AuthenticationPrincipal UserDetails user, Model model) {
        model.addAttribute("messages", messageService.getSent(user.getUsername()));
        model.addAttribute("box", "sent");
        return "messages/inbox"; // 동일 템플릿 재사용, box 변수로 구분
    }

    /* ── 쪽지 상세 ───────────────────────────────────────────── */

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal UserDetails user,
                         Model model) {
        model.addAttribute("message", messageService.getDetail(id, user.getUsername()));
        return "messages/detail";
    }

    /* ── 쪽지 작성 폼 ────────────────────────────────────────── */

    /**
     * to 파라미터가 있으면 수신자를 미리 채운 상태로 폼을 표시한다.
     * 프로필 페이지의 "쪽지 보내기" 버튼이 이 경로를 사용한다.
     */
    @GetMapping("/compose")
    public String composeForm(@RequestParam(required = false, defaultValue = "") String to,
                              Model model) {
        MessageRequest req = new MessageRequest();
        req.setTo(to);
        model.addAttribute("messageRequest", req);
        return "messages/compose";
    }

    /* ── 쪽지 전송 ───────────────────────────────────────────── */

    @PostMapping
    public String send(@Valid @ModelAttribute("messageRequest") MessageRequest request,
                       BindingResult bindingResult,
                       @AuthenticationPrincipal UserDetails user,
                       RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "messages/compose";
        }
        try {
            messageService.send(request, user.getUsername());
            redirectAttributes.addFlashAttribute("successMsg", "쪽지를 보냈습니다.");
            return "redirect:/messages/sent";
        } catch (IllegalArgumentException e) {
            bindingResult.rejectValue("to", "error.to", e.getMessage());
            return "messages/compose";
        }
    }

    /* ── 쪽지 삭제 ───────────────────────────────────────────── */

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(defaultValue = "inbox") String box,
                         @AuthenticationPrincipal UserDetails user) {
        messageService.delete(id, user.getUsername(), box);
        return "redirect:/messages" + ("sent".equals(box) ? "/sent" : "");
    }
}
