package com.effectivedisco.controller.web;

import com.effectivedisco.domain.Follow;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.BookmarkRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.FollowRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UserWebController 통합 테스트.
 *
 * 검증 대상:
 *   GET  /users/{username}            공개 프로필 (인증 불필요)
 *   GET  /settings                    인증 필요 / 미인증 시 로그인 리다이렉트
 *   POST /settings/profile            bio·이메일 변경
 *   POST /settings/password           비밀번호 변경 (성공·불일치·현재비밀번호오류)
 *   GET  /users/{username}/followers  팔로워 목록 (공개)
 *   GET  /users/{username}/following  팔로잉 목록 (공개)
 *   POST /users/{username}/follow     팔로우 등록 (인증 필요)
 *   POST /users/{username}/unfollow   팔로우 해제 (인증 필요)
 *   POST /users/{username}/block      차단 등록 (인증 필요)
 *   POST /users/{username}/unblock    차단 해제 (인증 필요)
 *
 * 인증: SecurityMockMvcRequestPostProcessors.user() 로 세션 인증을 시뮬레이션한다.
 * CSRF: 웹 FilterChain은 CSRF가 활성화되므로 POST에 .with(csrf()) 를 추가해야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserWebControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository        userRepository;
    @Autowired FollowRepository      followRepository;
    @Autowired BlockRepository       blockRepository;
    @Autowired BookmarkRepository    bookmarkRepository;
    @Autowired PostRepository        postRepository;
    @Autowired PostLikeRepository    postLikeRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PasswordEncoder       passwordEncoder;

    MockMvc mockMvc;
    User    testUser;   // 주요 테스트 사용자
    User    otherUser;  // 팔로우·차단 대상 사용자

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        postLikeRepository.deleteAll();
        bookmarkRepository.deleteAll();
        notificationRepository.deleteAll();
        blockRepository.deleteAll();
        followRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();

        testUser  = userRepository.save(User.builder()
                .username("webuser").email("webuser@test.com")
                .password(passwordEncoder.encode("pass123")).build());
        otherUser = userRepository.save(User.builder()
                .username("otheruser").email("other@test.com")
                .password(passwordEncoder.encode("pass123")).build());
    }

    // ── 공개 프로필 ───────────────────────────────────────────

    /**
     * GET /users/{username} 는 인증 없이도 200을 반환해야 한다.
     * 모델에 profile 속성이 포함되고 뷰는 "users/profile" 이어야 한다.
     */
    @Test
    void profile_public_returns200() throws Exception {
        mockMvc.perform(get("/users/{u}", testUser.getUsername()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("profile"))
                .andExpect(view().name("users/profile"));
    }

    // ── 설정 페이지 접근 제어 ─────────────────────────────────

    /**
     * 인증된 사용자는 GET /settings 에서 200과 함께 설정 폼을 받아야 한다.
     */
    @Test
    void settings_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/settings").with(user(testUser.getUsername())))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("profile"))
                .andExpect(view().name("users/settings"));
    }

    /**
     * 미인증 사용자가 GET /settings 에 접근하면 로그인 페이지로 리다이렉트되어야 한다.
     * SecurityConfig: /settings/** → authenticated()
     */
    @Test
    void settings_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ── 프로필(bio·이메일) 변경 ────────────────────────────────

    /**
     * POST /settings/profile 으로 bio를 변경하면 /settings 로 리다이렉트되어야 한다.
     * 플래시 속성 "bioMsg" 가 설정되어야 한다.
     */
    @Test
    void updateProfile_success_redirectsToSettings() throws Exception {
        mockMvc.perform(post("/settings/profile")
                        .with(csrf())
                        .with(user(testUser.getUsername()))
                        .param("email", "new@test.com")
                        .param("bio",   "안녕하세요 새 bio 입니다"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attributeExists("bioMsg"));
    }

    // ── 비밀번호 변경 ─────────────────────────────────────────

    /**
     * 올바른 현재 비밀번호와 일치하는 새 비밀번호를 제출하면
     * /settings 로 리다이렉트되고 passwordMsg 플래시가 설정되어야 한다.
     */
    @Test
    void changePassword_success_redirectsWithMsg() throws Exception {
        mockMvc.perform(post("/settings/password")
                        .with(csrf())
                        .with(user(testUser.getUsername()))
                        .param("currentPassword", "pass123")
                        .param("newPassword",      "newpass456")
                        .param("confirmPassword",  "newpass456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attributeExists("passwordMsg"));
    }

    /**
     * 새 비밀번호와 확인 비밀번호가 다르면 서비스에서 예외를 던지고
     * /settings 로 리다이렉트되며 passwordError 플래시가 설정되어야 한다.
     */
    @Test
    void changePassword_mismatch_redirectsWithError() throws Exception {
        mockMvc.perform(post("/settings/password")
                        .with(csrf())
                        .with(user(testUser.getUsername()))
                        .param("currentPassword", "pass123")
                        .param("newPassword",      "newpass456")
                        .param("confirmPassword",  "differentpass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attributeExists("passwordError"));
    }

    /**
     * 현재 비밀번호가 틀리면 서비스에서 예외를 던지고
     * /settings 로 리다이렉트되며 passwordError 플래시가 설정되어야 한다.
     */
    @Test
    void changePassword_wrongCurrentPassword_redirectsWithError() throws Exception {
        mockMvc.perform(post("/settings/password")
                        .with(csrf())
                        .with(user(testUser.getUsername()))
                        .param("currentPassword", "wrongpass")
                        .param("newPassword",      "newpass456")
                        .param("confirmPassword",  "newpass456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attributeExists("passwordError"));
    }

    // ── 팔로워/팔로잉 목록 ────────────────────────────────────

    /**
     * GET /users/{username}/followers 는 공개 엔드포인트로 200을 반환해야 한다.
     * 모델에 profile 과 followers 속성이 포함되어야 한다.
     */
    @Test
    void followersList_public_returns200() throws Exception {
        mockMvc.perform(get("/users/{u}/followers", testUser.getUsername()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("profile", "followers"))
                .andExpect(view().name("users/followers"));
    }

    /**
     * GET /users/{username}/following 은 공개 엔드포인트로 200을 반환해야 한다.
     * 팔로잉 목록이 비어있어도 정상적으로 응답해야 한다.
     */
    @Test
    void followingList_public_returns200() throws Exception {
        mockMvc.perform(get("/users/{u}/following", testUser.getUsername()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("profile", "followings"))
                .andExpect(view().name("users/following"));
    }

    /**
     * 팔로우 후 팔로워 목록에 해당 사용자가 포함되어야 한다.
     * 팔로우 관계를 직접 생성하고 목록 조회로 검증한다.
     */
    @Test
    void followersList_afterFollow_containsFollower() throws Exception {
        // testUser → otherUser 팔로우 관계 직접 생성
        followRepository.save(new Follow(testUser, otherUser));

        // otherUser 의 팔로워 목록에 testUser가 있어야 한다
        mockMvc.perform(get("/users/{u}/followers", otherUser.getUsername()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("followers"));
    }

    // ── 팔로우 등록/해제 ─────────────────────────────────────

    /**
     * 인증된 사용자가 타인 프로필에서 팔로우를 클릭하면
     * 해당 사용자 프로필로 리다이렉트되어야 한다.
     */
    @Test
    void follow_authenticated_redirectsToProfile() throws Exception {
        mockMvc.perform(post("/users/{u}/follow", otherUser.getUsername())
                        .with(csrf())
                        .with(user(testUser.getUsername())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/" + otherUser.getUsername()));
    }

    /**
     * 미인증 사용자가 팔로우를 시도하면 로그인 페이지로 리다이렉트되어야 한다.
     * SecurityConfig: POST /users/{username}/follow → authenticated()
     */
    @Test
    void follow_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/users/{u}/follow", otherUser.getUsername())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void unfollow_authenticated_redirectsToProfile() throws Exception {
        mockMvc.perform(post("/users/{u}/unfollow", otherUser.getUsername())
                        .with(csrf())
                        .with(user(testUser.getUsername())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/" + otherUser.getUsername()));
    }

    // ── 차단 등록/해제 ───────────────────────────────────────

    /**
     * 인증된 사용자가 차단을 누르면 해당 사용자 프로필로 리다이렉트되어야 한다.
     */
    @Test
    void block_authenticated_redirectsToProfile() throws Exception {
        mockMvc.perform(post("/users/{u}/block", otherUser.getUsername())
                        .with(csrf())
                        .with(user(testUser.getUsername())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/" + otherUser.getUsername()));
    }

    @Test
    void unblock_authenticated_redirectsToProfile() throws Exception {
        mockMvc.perform(post("/users/{u}/unblock", otherUser.getUsername())
                        .with(csrf())
                        .with(user(testUser.getUsername())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/" + otherUser.getUsername()));
    }
}
