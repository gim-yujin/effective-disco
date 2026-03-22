package com.effectivedisco.controller.web;

import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BlockRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 사용자 차단 웹 엔드포인트(UserWebController) 통합 테스트.
 *
 * POST /users/{username}/block — 차단 토글 엔드포인트.
 * - 인증된 사용자: 200 계열 처리 후 프로필 페이지로 리다이렉트
 * - 미인증 사용자: 로그인 페이지로 리다이렉트
 *
 * 토글 상태는 BlockRepository로 직접 확인한다.
 * MockMvc 요청은 자체 트랜잭션에서 커밋되므로 요청 후 blockRepository로 조회하면
 * 변경된 상태를 확인할 수 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BlockWebControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository  userRepository;
    @Autowired BlockRepository blockRepository;
    @Autowired PasswordEncoder passwordEncoder;

    MockMvc mockMvc;
    User testUser;
    User otherUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // DB 레벨 ON DELETE CASCADE로 블록 관계가 자동 삭제되므로
        // users 삭제만으로 충분하다.
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .username("testuser").email("test@test.com")
                .password(passwordEncoder.encode("pass")).build());
        otherUser = userRepository.save(User.builder()
                .username("otheruser").email("other@test.com")
                .password(passwordEncoder.encode("pass")).build());
    }

    // ── 차단 토글 ─────────────────────────────────────────────

    /**
     * 인증된 사용자가 다른 사용자를 차단하면 프로필 페이지로 리다이렉트되어야 한다.
     */
    @Test
    void blockUser_authenticated_redirectsToProfile() throws Exception {
        mockMvc.perform(post("/users/{username}/block", "otheruser")
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/otheruser"));
    }

    /**
     * 미인증 사용자가 차단 요청을 보내면 로그인 페이지로 리다이렉트되어야 한다.
     * POST /users/{username}/block은 anyRequest().authenticated() 에 의해 보호된다.
     */
    @Test
    void blockUser_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/users/{username}/block", "otheruser")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /**
     * 차단 토글을 두 번 호출하면 처음엔 차단, 두 번째엔 차단 해제가 되어야 한다.
     * BlockRepository로 최종 상태를 직접 검증한다.
     */
    @Test
    void blockUser_toggledTwice_firstBlocksThenUnblocks() throws Exception {
        // 첫 번째 요청: 차단 없음 → 차단 생성
        mockMvc.perform(post("/users/{username}/block", "otheruser")
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection());

        assertThat(blockRepository.existsByBlockerAndBlocked(testUser, otherUser))
                .as("첫 번째 토글 후 차단 관계가 생성되어야 한다").isTrue();

        // 두 번째 요청: 이미 차단 중 → 차단 해제
        mockMvc.perform(post("/users/{username}/block", "otheruser")
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection());

        assertThat(blockRepository.existsByBlockerAndBlocked(testUser, otherUser))
                .as("두 번째 토글 후 차단 관계가 제거되어야 한다").isFalse();
    }
}
