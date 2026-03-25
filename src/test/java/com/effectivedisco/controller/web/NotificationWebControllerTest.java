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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 *   목록 페이지 조회와 "모두 읽음" 액션은 분리된다.
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
                .andExpect(model().attributeExists("notificationPage"))
                .andExpect(model().attributeExists("unreadCount"))
                .andExpect(view().name("notifications/list"));
    }

    // ── 읽음 처리 ────────────────────────────────────────────

    /**
     * 문제 해결:
     * GET /notifications 는 목록 조회만 해야 한다.
     * 단순 페이지 방문이 bulk update 를 발생시키면 장시간 soak 에서 read-all 이 병목이 되므로,
     * 조회 후에도 unread row 는 그대로 남아 있어야 한다.
     */
    @Test
    void notifications_getDoesNotMarkUnreadAsRead() throws Exception {
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

        // 방문 후에도 unread 는 그대로 유지된다.
        assertEquals(1L, notificationRepository.countByRecipientAndIsReadFalse(testUser));
    }

    /**
     * 문제 해결:
     * "현재 페이지 읽음" 은 명시적 POST 액션으로만 수행한다.
     * 사용자가 버튼을 눌렀을 때만 unread row 가 읽음 처리되고, 다시 목록으로 리다이렉트되어야 한다.
     */
    @Test
    void notifications_markCurrentPageReadActionMarksUnreadAndRedirects() throws Exception {
        notificationRepository.save(Notification.builder()
                .recipient(testUser)
                .type(NotificationType.COMMENT)
                .message("테스트 댓글 알림 메시지입니다.")
                .link("/posts/1#comments")
                .build());

        assertEquals(1L, notificationRepository.countByRecipientAndIsReadFalse(testUser));

        mockMvc.perform(post("/notifications/read-page")
                        .param("page", "0")
                        .param("size", "20")
                        .with(user(testUser.getUsername()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/notifications?page=0&size=20"));

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
                .andExpect(model().attributeExists("notificationPage"))
                .andExpect(view().name("notifications/list"));
    }
}
