package com.effectivedisco.repository;

import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    /** 관리자 패널: 가입일 최신순 전체 사용자 목록 */
    List<User> findAllByOrderByCreatedAtDesc();

    /** 관리자 초기화: ROLE_ADMIN 계정이 이미 있는지 확인 */
    boolean existsByRole(String role);
}
