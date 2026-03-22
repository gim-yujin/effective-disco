package com.effectivedisco.config;

import com.effectivedisco.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 웹 컨트롤러의 모든 Model에 미읽음 알림 수를 자동으로 주입한다.
 * Thymeleaf 헤더 fragment가 ${unreadCount}로 뱃지를 표시하는 데 사용한다.
 * API 컨트롤러에는 적용되지 않도록 basePackages를 웹 컨트롤러 패키지로 한정한다.
 */
@ControllerAdvice(basePackages = "com.effectivedisco.controller.web")
@RequiredArgsConstructor
public class NotificationModelEnricher {

    private final NotificationService notificationService;

    /**
     * 로그인 상태이면 미읽음 알림 수를 반환한다.
     * 비로그인 상태이면 0을 반환한다.
     */
    @ModelAttribute("unreadCount")
    public long unreadCount(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return notificationService.getUnreadCount(authentication.getName());
        }
        return 0L;
    }
}
