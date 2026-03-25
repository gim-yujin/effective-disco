package com.effectivedisco.controller.web;

import com.effectivedisco.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationWebController {

    private final NotificationService notificationService;

    /**
     * 문제 해결:
     * GET /notifications 가 목록 조회와 read-all 상태 전환을 동시에 수행하면
     * 단순 페이지 진입만으로도 bulk update 가 반복되어 장시간 soak 에서 병목이 된다.
     * 조회는 조회만 수행하고, 읽음 처리는 명시적 POST 액션으로 분리한다.
     */
    @GetMapping
    public String list(@AuthenticationPrincipal UserDetails userDetails,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "30") int size,
                       Model model) {
        Slice<com.effectivedisco.dto.response.NotificationResponse> notificationPage =
                notificationService.getPage(userDetails.getUsername(), page, size);
        model.addAttribute("notifications", notificationPage.getContent());
        model.addAttribute("notificationPage", notificationPage);
        model.addAttribute("unreadCount", notificationService.getUnreadCount(userDetails.getUsername()));
        return "notifications/list";
    }

    /**
     * 문제 해결:
     * read-all 은 목록 조회와 분리된 명시 액션으로만 실행한다.
     * 이렇게 해야 알림 페이지를 여러 번 새로고침해도 매번 recipient 전체 unread row bulk update 가 발생하지 않는다.
     */
    @PostMapping("/read-all")
    public String markAllRead(@AuthenticationPrincipal UserDetails userDetails,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "30") int size) {
        notificationService.markAllAsRead(userDetails.getUsername());
        return "redirect:/notifications?page=" + Math.max(page, 0) + "&size=" + normalizePageSize(size);
    }

    private int normalizePageSize(int size) {
        return Math.max(1, Math.min(size, 50));
    }
}
