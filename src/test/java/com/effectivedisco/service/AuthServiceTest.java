package com.effectivedisco.service;

import com.effectivedisco.dto.request.LoginRequest;
import com.effectivedisco.dto.request.SignupRequest;
import com.effectivedisco.dto.response.AuthResponse;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.UserRepository;
import com.effectivedisco.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock AuthenticationManager authenticationManager;

    @InjectMocks AuthService authService;

    @Test
    void signup_success_returnsAuthResponse() {
        SignupRequest request = makeSignupRequest("user1", "user1@test.com", "pass123");
        given(userRepository.existsByUsername("user1")).willReturn(false);
        given(userRepository.existsByEmail("user1@test.com")).willReturn(false);
        given(passwordEncoder.encode("pass123")).willReturn("encoded");
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(jwtTokenProvider.generateToken("user1")).willReturn("jwt-token");

        AuthResponse response = authService.signup(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUsername()).isEqualTo("user1");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void signup_duplicateUsername_throwsException() {
        SignupRequest request = makeSignupRequest("user1", "user1@test.com", "pass123");
        given(userRepository.existsByUsername("user1")).willReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username already in use");
    }

    @Test
    void signup_duplicateEmail_throwsException() {
        SignupRequest request = makeSignupRequest("user1", "dup@test.com", "pass123");
        given(userRepository.existsByUsername("user1")).willReturn(false);
        given(userRepository.existsByEmail("dup@test.com")).willReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email already in use");
    }

    @Test
    void login_success_returnsAuthResponse() {
        LoginRequest request = makeLoginRequest("user1", "pass123");
        given(jwtTokenProvider.generateToken("user1")).willReturn("jwt-token");

        AuthResponse response = authService.login(request);

        verify(authenticationManager).authenticate(
                any(UsernamePasswordAuthenticationToken.class));
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUsername()).isEqualTo("user1");
    }

    @Test
    void login_badCredentials_throwsException() {
        LoginRequest request = makeLoginRequest("user1", "wrong");
        given(authenticationManager.authenticate(any()))
                .willThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);
    }

    // --- helpers ---

    private SignupRequest makeSignupRequest(String username, String email, String password) {
        SignupRequest req = new SignupRequest();
        req.setUsername(username);
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    private LoginRequest makeLoginRequest(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }
}
