package com.effectivedisco.config;

import com.effectivedisco.service.MessageService;
import com.effectivedisco.service.NotificationService;
import com.effectivedisco.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 웹 컨트롤러의 모든 Model에 미읽음 알림 수·쪽지 수를 자동으로 주입한다.
 * Thymeleaf 헤더 fragment가 뱃지 표시에 사용한다.
 * API 컨트롤러에는 적용되지 않도록 basePackages를 웹 컨트롤러 패키지로 한정한다.
 */
@ControllerAdvice(basePackages = "com.effectivedisco.controller.web")
@RequiredArgsConstructor
public class NotificationModelEnricher {

    private final NotificationService notificationService;
    private final MessageService      messageService;
    private final ReportService       reportService;

    /** 미읽음 알림 수 (댓글·좋아요 등) */
    @ModelAttribute("unreadCount")
    public long unreadCount(Authentication authentication) {
        if (isLoggedIn(authentication)) {
            return notificationService.getUnreadCount(authentication.getName());
        }
        return 0L;
    }

    /** 미읽음 쪽지 수 */
    @ModelAttribute("unreadMessageCount")
    public long unreadMessageCount(Authentication authentication) {
        if (isLoggedIn(authentication)) {
            return messageService.getUnreadCount(authentication.getName());
        }
        return 0L;
    }

    /** 미처리 신고 수 (관리자 헤더 배지용) */
    @ModelAttribute("pendingReportCount")
    public long pendingReportCount(Authentication authentication) {
        if (isAdmin(authentication)) {
            return reportService.getPendingCount();
        }
        return 0L;
    }

    private boolean isAdmin(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream()
                       .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private boolean isLoggedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getName());
    }
}
