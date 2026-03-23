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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthWebController 통합 테스트.
 *
 * 검증 대상:
 *   GET  /login    로그인 폼 표시 (permitAll)
 *   GET  /signup   회원가입 폼 표시 (permitAll)
 *   POST /signup   회원가입 처리
 *     - 성공: /login?signup=success 리다이렉트
 *     - username 중복: 폼으로 돌아가며 "error" 모델 속성 설정
 *     - email 중복:    폼으로 돌아가며 "error" 모델 속성 설정
 *
 * 인증:
 *   /login, /signup 모두 SecurityConfig에서 permitAll 이므로 인증 없이 테스트한다.
 *   POST /signup 는 CSRF 토큰이 필요하다 (webFilterChain CSRF 활성화).
 *
 * 중복 체크:
 *   AuthService가 username·email 중복 시 IllegalArgumentException 을 발생시키고
 *   컨트롤러가 catch 해 "error" 모델 속성을 설정한 뒤 폼을 다시 반환한다.
 *
 * 주의:
 *   AdminDataInitializer가 애플리케이션 시작 시 username="admin" 을 생성한다.
 *   충돌을 피하기 위해 테스트에서는 "admin" 이 아닌 username을 사용한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthWebControllerTest {

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
    }

    // ── 로그인 폼 ─────────────────────────────────────────────

    /**
     * GET /login 은 인증 없이도 200과 함께 로그인 폼을 반환해야 한다.
     * SecurityConfig: /login → permitAll()
     * Spring Security 기본 설정으로 formLogin().loginPage("/login") 이 연결된다.
     */
    @Test
    void loginPage_returns200() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    // ── 회원가입 폼 ───────────────────────────────────────────

    /**
     * GET /signup 은 인증 없이도 200과 함께 회원가입 폼을 반환해야 한다.
     * 모델에 "signupRequest" 속성이 포함되어야 한다.
     */
    @Test
    void signupPage_returns200() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("signupRequest"))
                .andExpect(view().name("auth/signup"));
    }

    // ── 회원가입 처리 ─────────────────────────────────────────

    /**
     * 유효한 정보로 POST /signup 하면 /login?signup=success 로 리다이렉트된다.
     * AuthService.signup() 성공 후 컨트롤러가 "redirect:/login?signup=success" 를 반환한다.
     */
    @Test
    void signup_success_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("username", "newuser123")
                        .param("email",    "newuser@test.com")
                        .param("password", "securepass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?signup=success"));
    }

    /**
     * 이미 존재하는 username 으로 가입하면 AuthService 가 IllegalArgumentException 을 던지고
     * 컨트롤러가 catch 해 회원가입 폼을 다시 표시한다.
     * 모델에 "error" 속성이 포함되어야 한다.
     */
    @Test
    void signup_duplicateUsername_returnsFormWithError() throws Exception {
        // 동일 username 을 가진 사용자 미리 생성
        userRepository.save(User.builder()
                .username("existinguser").email("existing@test.com")
                .password(passwordEncoder.encode("pass")).build());

        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("username", "existinguser")   // 중복 username
                        .param("email",    "another@test.com")
                        .param("password", "securepass"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attributeExists("signupRequest"))
                .andExpect(view().name("auth/signup"));
    }

    /**
     * 이미 존재하는 email 로 가입하면 회원가입 폼이 다시 표시된다.
     * 모델에 "error" 속성이 포함되어야 한다.
     *
     * AuthService: email 중복 시 "이미 사용 중인 이메일입니다" → IllegalArgumentException
     */
    @Test
    void signup_duplicateEmail_returnsFormWithError() throws Exception {
        // 동일 email 을 가진 사용자 미리 생성
        userRepository.save(User.builder()
                .username("uniqueuser").email("duplicate@test.com")
                .password(passwordEncoder.encode("pass")).build());

        mockMvc.perform(post("/signup")
                        .with(csrf())
                        .param("username", "brandnewuser")
                        .param("email",    "duplicate@test.com")   // 중복 email
                        .param("password", "securepass"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attributeExists("signupRequest"))
                .andExpect(view().name("auth/signup"));
    }
}
