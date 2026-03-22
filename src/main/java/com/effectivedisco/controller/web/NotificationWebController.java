package com.effectivedisco.controller.web;

import com.effectivedisco.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationWebController {

    private final NotificationService notificationService;

    /**
     * 알림 목록 페이지.
     * 방문 즉시 미읽음 알림을 모두 읽음으로 표시하고 전체 목록을 반환한다.
     */
    @GetMapping
    public String list(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        model.addAttribute("notifications",
                notificationService.getAndMarkAllRead(userDetails.getUsername()));
        return "notifications/list";
    }
}
