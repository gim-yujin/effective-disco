package com.effectivedisco.controller.web;

import com.effectivedisco.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationWebController {

    private final NotificationService notificationService;

    /**
     * 알림 목록 페이지.
     * 방문 즉시 미읽음 알림을 모두 읽음으로 표시하되,
     * 현재 batch 만 렌더링해 full list DOM/DB 비용을 누적하지 않는다.
     */
    @GetMapping
    public String list(@AuthenticationPrincipal UserDetails userDetails,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "30") int size,
                       Model model) {
        Slice<com.effectivedisco.dto.response.NotificationResponse> notificationPage =
                notificationService.getAndMarkAllReadPage(userDetails.getUsername(), page, size);
        model.addAttribute("notifications", notificationPage.getContent());
        model.addAttribute("notificationPage", notificationPage);
        return "notifications/list";
    }
}
