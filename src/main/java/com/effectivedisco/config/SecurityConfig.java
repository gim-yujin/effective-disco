package com.effectivedisco.config;

import com.effectivedisco.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.load-test.enabled:false}")
    private boolean loadTestEnabled;

    /** /api/** — JWT, stateless */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/posts/**", "/api/boards/**", "/api/search/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                res.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED)))
                .build();
    }

    /** /** — 세션 기반 폼 로그인 */
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> {
                    if (loadTestEnabled) {
                        // 문제 해결:
                        // load-test reset endpoint는 k6/쉘 스크립트가 토큰 없이 POST 해야 하므로
                        // 해당 내부 경로에 한해 CSRF 검사를 제외해 자동화가 막히지 않게 한다.
                        csrf.ignoringRequestMatchers("/internal/load-test/**");
                    }
                })
                .authorizeHttpRequests(auth -> {
                    // 비인증 사용자도 접근 가능한 경로: 로그인/회원가입/비밀번호재설정/정적자원
                    auth.requestMatchers("/login", "/signup", "/css/**", "/uploads/**",
                                    "/forgot-password", "/reset-password").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/", "/boards/**", "/posts/**", "/users/**", "/search").permitAll();

                    if (loadTestEnabled) {
                        // 문제 해결:
                        // k6가 테스트 직후 서버 내부 지표를 읽어야 p95/p99와 DB pool 병목을 함께 비교할 수 있다.
                        // 다만 운영 기본 프로필에는 노출되면 안 되므로 loadtest 프로필에서만 내부 경로를 연다.
                        auth.requestMatchers("/internal/load-test/**").permitAll();
                    }

                    // /admin/** 은 ROLE_ADMIN 만 접근 가능
                    auth.requestMatchers("/admin/**").hasAuthority("ROLE_ADMIN");
                    // /notifications, /messages, /reports, /settings, /bookmarks, /sse, /feed 는 로그인 필요
                    auth.requestMatchers("/notifications/**", "/messages/**", "/reports/**",
                                    "/settings/**", "/bookmarks/**", "/bookmarks", "/sse/**", "/feed",
                                    "/blocks", "/drafts/**").authenticated();
                    auth.anyRequest().authenticated();
                })
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl("/login")
                        .permitAll())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
