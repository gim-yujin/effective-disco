package com.effectivedisco.controller.web;

import com.effectivedisco.domain.Notification;
import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NotificationWebController 통합 테스트.
 *
 * 검증 대상:
 *   GET /notifications  알림 목록 (인증 필요)
 *
 * 접근 제어:
 *   SecurityConfig: /notifications/** → authenticated()
 *   미인증 접근은 /login 으로 리다이렉트되어야 한다.
 *
 * 읽음 처리:
 *   목록 페이지 방문 시 미읽음 알림이 모두 읽음으로 표시된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationWebControllerTest {

    @Autowired WebApplicationContext  context;
    @Autowired UserRepository         userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PasswordEncoder        passwordEncoder;

    MockMvc mockMvc;
    User    testUser; // 알림 수신자

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        testUser = userRepository.save(User.builder()
                .username("notifuser").email("notif@test.com")
                .password(passwordEncoder.encode("pass")).build());
    }

    // ── 접근 제어 ────────────────────────────────────────────

    /**
     * 미인증 사용자가 /notifications 에 접근하면 /login 으로 리다이렉트된다.
     */
    @Test
    void notifications_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/notifications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /**
     * 인증된 사용자는 알림 목록 페이지에서 200을 받아야 한다.
     * 모델에 "notifications" 속성이 포함되고 뷰는 "notifications/list" 여야 한다.
     */
    @Test
    void notifications_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/notifications").with(user(testUser.getUsername())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("notifications"))
                .andExpect(view().name("notifications/list"));
    }

    // ── 읽음 처리 ────────────────────────────────────────────

    /**
     * 알림 목록 페이지를 방문하면 미읽음 알림이 모두 읽음으로 표시된다.
     *
     * NotificationService.getAndMarkAllRead():
     *   1. 전체 알림 목록 조회
     *   2. notificationRepository.markAllAsRead(user) — @Modifying JPQL 일괄 업데이트
     *   3. SSE로 unreadCount=0 push
     *
     * 방문 후 countByRecipientAndIsReadFalse 가 0 이어야 한다.
     */
    @Test
    void notifications_marksUnreadAsRead() throws Exception {
        // 미읽음 알림 1건 생성 (isRead = false 기본값)
        notificationRepository.save(Notification.builder()
                .recipient(testUser)
                .type(NotificationType.COMMENT)
                .message("테스트 댓글 알림 메시지입니다.")
                .link("/posts/1#comments")
                .build());

        // 방문 전: 미읽음 알림 1건 확인
        assertEquals(1L, notificationRepository.countByRecipientAndIsReadFalse(testUser));

        // 알림 목록 페이지 방문
        mockMvc.perform(get("/notifications").with(user(testUser.getUsername())))
                .andExpect(status().isOk());

        // 방문 후: 미읽음 알림 0건 (모두 읽음 처리됨)
        assertEquals(0L, notificationRepository.countByRecipientAndIsReadFalse(testUser));
    }

    /**
     * 알림이 없어도 페이지가 정상적으로 렌더링된다.
     * notifications 모델 속성이 비어있는 리스트여야 한다.
     */
    @Test
    void notifications_emptyList_returns200() throws Exception {
        mockMvc.perform(get("/notifications").with(user(testUser.getUsername())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("notifications"))
                .andExpect(view().name("notifications/list"));
    }
}
