package com.effectivedisco.controller.web;

import com.effectivedisco.domain.PasswordResetToken;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.PasswordResetTokenRepository;
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

import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PasswordResetWebController 통합 테스트.
 *
 * 검증 대상:
 *   GET  /forgot-password          이메일 입력 폼 표시
 *   POST /forgot-password          재설정 이메일 발송 요청 (항상 성공 메시지)
 *   GET  /reset-password?token=... 새 비밀번호 입력 폼
 *   POST /reset-password           비밀번호 변경 처리
 *     - 성공: 유효 토큰 + 비밀번호 일치 → /login 리다이렉트
 *     - 실패: 비밀번호 불일치 → 같은 폼으로 errorMsg
 *     - 실패: 잘못된 토큰   → 같은 폼으로 errorMsg
 *     - 실패: 만료된 토큰   → 같은 폼으로 errorMsg
 *     - 실패: 사용된 토큰   → 같은 폼으로 errorMsg
 *
 * 모든 경로는 SecurityConfig에서 permitAll이므로 인증 없이 테스트한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PasswordResetWebControllerTest {

    @Autowired WebApplicationContext         context;
    @Autowired UserRepository                userRepository;
    @Autowired PasswordResetTokenRepository  tokenRepository;
    @Autowired PasswordEncoder               passwordEncoder;

    MockMvc mockMvc;
    User    testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        testUser = userRepository.save(User.builder()
                .username("resetuser").email("reset@test.com")
                .password(passwordEncoder.encode("oldpass123")).build());
    }

    // ── 이메일 입력 폼 ────────────────────────────────────────

    /**
     * GET /forgot-password 는 인증 없이 200과 함께 폼 뷰를 반환해야 한다.
     */
    @Test
    void forgotPasswordForm_returns200() throws Exception {
        mockMvc.perform(get("/forgot-password"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/forgot-password"));
    }

    // ── 재설정 이메일 발송 요청 ───────────────────────────────

    /**
     * 존재하는 이메일로 POST 하면 항상 /forgot-password 로 리다이렉트된다.
     * 보안상 이유로 이메일 존재 여부와 무관하게 동일한 성공 메시지를 표시한다.
     */
    @Test
    void requestReset_existingEmail_redirectsWithMsg() throws Exception {
        mockMvc.perform(post("/forgot-password")
                        .with(csrf())
                        .param("email", "reset@test.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"))
                .andExpect(flash().attributeExists("msg"));
    }

    /**
     * 존재하지 않는 이메일로 POST 해도 동일하게 리다이렉트된다.
     * 계정 열거 공격(account enumeration) 방지를 위해 항상 성공 메시지를 표시한다.
     */
    @Test
    void requestReset_nonExistingEmail_alsoRedirectsWithMsg() throws Exception {
        mockMvc.perform(post("/forgot-password")
                        .with(csrf())
                        .param("email", "nonexistent@test.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"))
                .andExpect(flash().attributeExists("msg"));
    }

    // ── 새 비밀번호 입력 폼 ────────────────────────────────────

    /**
     * GET /reset-password?token=xxx 는 토큰을 모델에 담아 폼을 표시해야 한다.
     * 토큰의 유효성은 이 시점에 검증하지 않는다 (제출 시에만 검증).
     */
    @Test
    void resetPasswordForm_anyToken_returns200() throws Exception {
        mockMvc.perform(get("/reset-password")
                        .param("token", "임의토큰값"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("token", "임의토큰값"))
                .andExpect(view().name("auth/reset-password"));
    }

    // ── 비밀번호 변경 처리 ────────────────────────────────────

    /**
     * 유효한 토큰과 일치하는 새 비밀번호를 제출하면
     * /login 으로 리다이렉트되고 성공 플래시(msg)가 설정되어야 한다.
     */
    @Test
    void resetPassword_validToken_redirectsToLoginWithMsg() throws Exception {
        // 유효한 토큰 생성 (만료 1시간 후)
        String tokenValue = "valid-reset-token-uuid";
        tokenRepository.save(new PasswordResetToken(
                tokenValue, testUser, LocalDateTime.now().plusHours(1)));

        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token",           tokenValue)
                        .param("newPassword",     "newpass456")
                        .param("confirmPassword", "newpass456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attributeExists("msg"));
    }

    /**
     * 새 비밀번호와 확인 비밀번호가 다르면 같은 폼으로 돌아가며 errorMsg 가 설정된다.
     */
    @Test
    void resetPassword_mismatchPasswords_returnsFormWithError() throws Exception {
        String tokenValue = "valid-token-for-mismatch";
        tokenRepository.save(new PasswordResetToken(
                tokenValue, testUser, LocalDateTime.now().plusHours(1)));

        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token",           tokenValue)
                        .param("newPassword",     "newpass456")
                        .param("confirmPassword", "differentpass"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("errorMsg"))
                .andExpect(model().attribute("token", tokenValue))
                .andExpect(view().name("auth/reset-password"));
    }

    /**
     * 존재하지 않는 토큰을 제출하면 같은 폼으로 돌아가며 errorMsg 가 설정된다.
     */
    @Test
    void resetPassword_invalidToken_returnsFormWithError() throws Exception {
        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token",           "completely-invalid-token")
                        .param("newPassword",     "newpass456")
                        .param("confirmPassword", "newpass456"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("errorMsg"))
                .andExpect(view().name("auth/reset-password"));
    }

    /**
     * 만료된 토큰(expiresAt 이 과거)을 제출하면 같은 폼으로 돌아가며 errorMsg 가 설정된다.
     */
    @Test
    void resetPassword_expiredToken_returnsFormWithError() throws Exception {
        // 이미 만료된 토큰 생성 (1시간 전에 만료)
        String tokenValue = "expired-reset-token";
        tokenRepository.save(new PasswordResetToken(
                tokenValue, testUser, LocalDateTime.now().minusHours(1)));

        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token",           tokenValue)
                        .param("newPassword",     "newpass456")
                        .param("confirmPassword", "newpass456"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("errorMsg"))
                .andExpect(view().name("auth/reset-password"));
    }

    /**
     * 이미 사용된 토큰(used=true)으로 재설정을 시도하면 같은 폼으로 돌아가며 errorMsg 가 설정된다.
     */
    @Test
    void resetPassword_usedToken_returnsFormWithError() throws Exception {
        String tokenValue = "already-used-token";
        PasswordResetToken token = new PasswordResetToken(
                tokenValue, testUser, LocalDateTime.now().plusHours(1));
        token.markUsed(); // 이미 사용 처리
        tokenRepository.save(token);

        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token",           tokenValue)
                        .param("newPassword",     "newpass456")
                        .param("confirmPassword", "newpass456"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("errorMsg"))
                .andExpect(view().name("auth/reset-password"));
    }

    /**
     * 새 비밀번호가 6자 미만이면 서비스 유효성 검사에서 예외가 발생하고
     * 같은 폼으로 돌아가며 errorMsg 가 설정된다.
     */
    @Test
    void resetPassword_tooShortPassword_returnsFormWithError() throws Exception {
        String tokenValue = "valid-token-short-pass";
        tokenRepository.save(new PasswordResetToken(
                tokenValue, testUser, LocalDateTime.now().plusHours(1)));

        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token",           tokenValue)
                        .param("newPassword",     "abc")   // 6자 미만
                        .param("confirmPassword", "abc"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("errorMsg"))
                .andExpect(view().name("auth/reset-password"));
    }
}
