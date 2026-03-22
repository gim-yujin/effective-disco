package com.effectivedisco.service;

import com.effectivedisco.domain.PasswordResetToken;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.PasswordResetTokenRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PasswordResetService 단위 테스트.
 *
 * Optional<JavaMailSender> 의존성을 직접 생성자에 Optional.empty()로 전달한다.
 * (JavaMailSender 미설정 환경: 콘솔 로그로 링크를 출력하고 예외 없이 종료)
 * @Value("${app.base-url}") 필드는 ReflectionTestUtils로 설정한다.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock UserRepository               userRepository;
    @Mock PasswordResetTokenRepository tokenRepository;
    @Mock PasswordEncoder              passwordEncoder;

    PasswordResetService service;

    @BeforeEach
    void setUp() {
        // Optional<JavaMailSender> = empty → 메일 발송 대신 로그 출력 분기
        service = new PasswordResetService(
                userRepository, tokenRepository, passwordEncoder, Optional.empty());
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");
    }

    // ── requestReset ──────────────────────────────────────

    @Test
    void requestReset_validEmail_deletesOldTokenAndSavesNew() {
        User user = makeUser("alice@test.com");

        given(userRepository.findByEmail("alice@test.com")).willReturn(Optional.of(user));

        service.requestReset("alice@test.com");

        // 기존 토큰 삭제 후 새 토큰 저장
        verify(tokenRepository).deleteByUser(user);
        verify(tokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void requestReset_unknownEmail_doesNothingAndNoException() {
        // 존재하지 않는 이메일 — 계정 열거 공격 방지: 예외 없이 조용히 리턴
        given(userRepository.findByEmail("ghost@test.com")).willReturn(Optional.empty());

        service.requestReset("ghost@test.com");

        verify(tokenRepository, never()).save(any());
    }

    // ── resetPassword ─────────────────────────────────────

    @Test
    void resetPassword_success_encodesNewPasswordAndMarksTokenUsed() {
        User user = makeUser("alice@test.com");
        PasswordResetToken token = makeValidToken("valid-token", user);

        given(tokenRepository.findByToken("valid-token")).willReturn(Optional.of(token));
        given(passwordEncoder.encode("newpass123")).willReturn("encoded_new");

        service.resetPassword("valid-token", "newpass123", "newpass123");

        assertThat(user.getPassword()).isEqualTo("encoded_new");
        assertThat(token.isUsed()).isTrue();
    }

    @Test
    void resetPassword_confirmMismatch_throwsException() {
        assertThatThrownBy(() ->
                service.resetPassword("token", "password1", "password2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("새 비밀번호 확인이 일치하지 않습니다");
    }

    @Test
    void resetPassword_tooShortPassword_throwsException() {
        assertThatThrownBy(() ->
                service.resetPassword("token", "short", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6자 이상");
    }

    @Test
    void resetPassword_invalidToken_throwsException() {
        given(tokenRepository.findByToken("bad-token")).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.resetPassword("bad-token", "newpass1", "newpass1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("유효하지 않은 재설정 링크");
    }

    @Test
    void resetPassword_expiredToken_throwsException() {
        User user = makeUser("alice@test.com");
        // 만료된 토큰: expiresAt이 과거
        PasswordResetToken token = new PasswordResetToken(
                "expired-token", user, LocalDateTime.now().minusHours(2));

        given(tokenRepository.findByToken("expired-token")).willReturn(Optional.of(token));

        assertThatThrownBy(() ->
                service.resetPassword("expired-token", "newpass1", "newpass1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void resetPassword_alreadyUsedToken_throwsException() {
        User user = makeUser("alice@test.com");
        PasswordResetToken token = makeValidToken("used-token", user);
        token.markUsed(); // 이미 사용된 토큰

        given(tokenRepository.findByToken("used-token")).willReturn(Optional.of(token));

        assertThatThrownBy(() ->
                service.resetPassword("used-token", "newpass1", "newpass1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만료");
    }

    // ── helpers ───────────────────────────────────────────

    private User makeUser(String email) {
        return User.builder()
                .username("alice").email(email).password("encoded_old").build();
    }

    private PasswordResetToken makeValidToken(String tokenValue, User user) {
        return new PasswordResetToken(
                tokenValue, user, LocalDateTime.now().plusMinutes(60));
    }
}
