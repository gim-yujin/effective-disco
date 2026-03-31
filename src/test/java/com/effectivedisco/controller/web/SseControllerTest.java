package com.effectivedisco.controller.web;

import com.effectivedisco.domain.User;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SseController 통합 테스트.
 *
 * GET /sse/notifications
 * - 세션 인증된 사용자만 접근 가능 (webFilterChain)
 * - 응답은 text/event-stream 으로 비동기 반환된다
 *
 * SSE 엔드포인트는 비동기 응답(SseEmitter)을 반환하므로
 * MockMvc에서는 asyncStarted() 로 비동기 핸들링이 시작됐는지 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SseControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository        userRepository;
    @Autowired PasswordEncoder       passwordEncoder;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // 테스트용 사용자 생성 (세션 인증에 사용)
        if (userRepository.findByUsername("sseuser").isEmpty()) {
            userRepository.save(User.builder()
                    .username("sseuser")
                    .email("sse@test.com")
                    .password(passwordEncoder.encode("pass123"))
                    .build());
        }
    }

    // ── 인증된 사용자 접근 ────────────────────────────────

    @Test
    void subscribe_withAuth_returns200AndStartsAsync() throws Exception {
        // 세션 인증 사용자로 SSE 엔드포인트에 접근
        // SseEmitter 반환 → MockMvc에서는 비동기 시작됨을 검증
        mockMvc.perform(get("/sse/notifications")
                        .with(user("sseuser")))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    // ── 미인증 사용자 접근 ────────────────────────────────

    @Test
    void subscribe_withoutAuth_redirectsToLogin() throws Exception {
        // 인증 없이 접근하면 로그인 페이지로 리다이렉트 (webFilterChain의 폼 로그인)
        mockMvc.perform(get("/sse/notifications"))
                .andExpect(status().is3xxRedirection());
    }
}
