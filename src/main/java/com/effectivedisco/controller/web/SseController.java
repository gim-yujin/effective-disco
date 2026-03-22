package com.effectivedisco.controller.web;

import com.effectivedisco.service.NotificationService;
import com.effectivedisco.service.SseEmitterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events 엔드포인트.
 *
 * GET /sse/notifications
 * - 세션 인증(webFilterChain)으로 보호된다.
 * - 브라우저의 EventSource가 이 URL에 연결하면 SseEmitter를 반환하고,
 *   이후 알림이 생성될 때마다 "unread-count" 이벤트를 push한다.
 * - produces = TEXT_EVENT_STREAM_VALUE 을 명시해 Spring MVC가
 *   응답 Content-Type을 text/event-stream 으로 설정하도록 한다.
 */
@RestController
@RequiredArgsConstructor
public class SseController {

    private final SseEmitterService  sseEmitterService;
    private final NotificationService notificationService;

    /**
     * SSE 구독 엔드포인트.
     * 연결 즉시 현재 미읽음 수를 "init" 이벤트로 전송하고,
     * 이후 알림 이벤트를 실시간으로 수신한다.
     *
     * @param userDetails Spring Security 세션에서 주입되는 현재 로그인 사용자
     * @return SseEmitter (Spring MVC가 응답 스트림으로 관리)
     */
    @GetMapping(value = "/sse/notifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails.getUsername();
        // 현재 미읽음 수를 초기값으로 전달 — 연결 즉시 뱃지를 최신 상태로 동기화
        long initialCount = notificationService.getUnreadCount(username);
        return sseEmitterService.subscribe(username, initialCount);
    }
}
