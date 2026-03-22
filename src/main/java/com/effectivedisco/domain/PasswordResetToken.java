package com.effectivedisco.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 비밀번호 재설정 토큰 엔티티.
 *
 * 흐름:
 * 1. 사용자가 이메일을 입력하면 UUID 토큰을 생성하고 DB에 저장한다.
 * 2. 사용자에게 재설정 링크(토큰 포함)를 이메일로 발송한다.
 * 3. 사용자가 링크를 클릭하면 토큰을 검증하고 비밀번호를 변경한다.
 * 4. 사용된 토큰은 used = true 로 표시해 재사용을 방지한다.
 *
 * 보안:
 * - 토큰 유효 기간: 발급 후 1시간
 * - 1회 사용 후 만료 (used 플래그)
 * - 재발급 시 기존 미사용 토큰을 먼저 삭제
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@NoArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID 기반 랜덤 토큰 문자열. URL 안에 포함된다. */
    @Column(nullable = false, unique = true)
    private String token;

    /** 이 토큰을 발급받은 사용자 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 토큰 만료 시각. 발급 후 1시간 */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /**
     * 사용 완료 여부.
     * true 이면 이미 비밀번호 재설정에 사용된 토큰이므로 재사용 불가.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean used = false;

    public PasswordResetToken(String token, User user, LocalDateTime expiresAt) {
        this.token     = token;
        this.user      = user;
        this.expiresAt = expiresAt;
    }

    /** 토큰이 현재 사용 가능한지 확인: 미사용 && 만료되지 않음 */
    public boolean isValid() {
        return !used && LocalDateTime.now().isBefore(expiresAt);
    }

    /** 비밀번호 재설정 완료 후 토큰을 사용 처리한다. */
    public void markUsed() {
        this.used = true;
    }
}
