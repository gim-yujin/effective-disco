package com.effectivedisco.config;

import com.effectivedisco.domain.User;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 시작 시 ROLE_ADMIN 계정이 없으면 기본 관리자를 생성한다.
 * BoardDataInitializer 보다 먼저 실행되어야 하므로 @Order(1)을 부여한다.
 *
 * 기본 계정: admin / admin123
 * 운영 환경에서는 첫 로그인 후 반드시 비밀번호를 변경해야 한다.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AdminDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.existsByRole("ROLE_ADMIN")) {
            return; // 관리자 계정이 이미 있으면 건너뜀
        }
        User admin = User.builder()
                .username("admin")
                .email("admin@effectivedisco.local")
                .password(passwordEncoder.encode("admin123"))
                .build();
        admin.promoteToAdmin();
        userRepository.save(admin);
        log.info("기본 관리자 계정이 생성되었습니다 (admin / admin123). 비밀번호를 변경하세요.");
    }
}
