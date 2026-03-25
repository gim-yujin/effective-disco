package com.effectivedisco.repository;

import com.effectivedisco.domain.PasswordResetToken;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** 토큰 문자열로 엔티티 조회 (재설정 링크 클릭 시 사용) */
    Optional<PasswordResetToken> findByToken(String token);

    /**
     * 특정 사용자의 기존 토큰을 모두 삭제한다.
     * 재발급 요청 시 이전 토큰을 먼저 정리해 혼선을 방지한다.
     */
    void deleteByUser(User user);

    long countByUserUsernameStartingWith(String prefix);
}
