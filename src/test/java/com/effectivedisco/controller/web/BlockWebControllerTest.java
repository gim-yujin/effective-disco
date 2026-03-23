package com.effectivedisco.controller.web;

import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.BookmarkRepository;
import com.effectivedisco.repository.FollowRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
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
 * POST /users/{username}/block   — 차단 등록 엔드포인트.
 * POST /users/{username}/unblock — 차단 해제 엔드포인트.
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
    @Autowired PostRepository postRepository;
    @Autowired PostLikeRepository postLikeRepository;
    @Autowired BookmarkRepository bookmarkRepository;
    @Autowired FollowRepository followRepository;
    @Autowired NotificationRepository notificationRepository;
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

        postLikeRepository.deleteAll();
        bookmarkRepository.deleteAll();
        followRepository.deleteAll();
        notificationRepository.deleteAll();
        postRepository.deleteAll();
        blockRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .username("testuser").email("test@test.com")
                .password(passwordEncoder.encode("pass")).build());
        otherUser = userRepository.save(User.builder()
                .username("otheruser").email("other@test.com")
                .password(passwordEncoder.encode("pass")).build());
    }

    // ── 차단 등록/해제 ───────────────────────────────────────

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
     * 같은 차단 요청을 반복해도 차단 상태가 유지되어야 한다.
     * 문제 해결 검증:
     * 토글 방식이었다면 두 번째 요청이 상태를 뒤집었겠지만, 이제는 block 전용 엔드포인트라 no-op다.
     */
    @Test
    void blockUser_repeatedBlock_keepsBlocked() throws Exception {
        mockMvc.perform(post("/users/{username}/block", "otheruser")
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection());

        assertThat(blockRepository.existsByBlockerAndBlocked(testUser, otherUser))
                .as("첫 번째 block 후 차단 관계가 생성되어야 한다").isTrue();

        mockMvc.perform(post("/users/{username}/block", "otheruser")
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection());

        assertThat(blockRepository.existsByBlockerAndBlocked(testUser, otherUser))
                .as("같은 block 요청을 반복해도 차단 상태가 유지되어야 한다").isTrue();
    }

    @Test
    void unblockUser_repeatedUnblock_keepsUnblocked() throws Exception {
        blockRepository.save(new com.effectivedisco.domain.Block(testUser, otherUser));

        mockMvc.perform(post("/users/{username}/unblock", "otheruser")
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/otheruser"));

        assertThat(blockRepository.existsByBlockerAndBlocked(testUser, otherUser))
                .as("첫 번째 unblock 후 차단 관계가 제거되어야 한다").isFalse();

        mockMvc.perform(post("/users/{username}/unblock", "otheruser")
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/otheruser"));

        assertThat(blockRepository.existsByBlockerAndBlocked(testUser, otherUser))
                .as("같은 unblock 요청을 반복해도 차단 해제 상태가 유지되어야 한다").isFalse();
    }
}
