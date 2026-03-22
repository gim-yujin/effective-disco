package com.effectivedisco.service;

import com.effectivedisco.domain.PasswordResetToken;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.PasswordResetTokenRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 비밀번호 재설정 서비스.
 *
 * 메일 발송 설정(spring.mail.host)이 없어도 기동 가능하다.
 * 메일 서버가 미설정된 개발 환경에서는 재설정 링크를 콘솔 로그(INFO)에 출력한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    /** 토큰 유효 기간 (분). 기본 60분 */
    private static final int TOKEN_EXPIRY_MINUTES = 60;

    private final UserRepository              userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder             passwordEncoder;

    /**
     * JavaMailSender 는 spring.mail.host 가 설정된 경우에만 빈으로 등록된다.
     * Optional 로 주입해 미설정 환경에서도 서비스가 정상 동작하도록 한다.
     */
    private final Optional<JavaMailSender> mailSender;

    /** 이메일에 포함될 재설정 링크의 앞부분 (app.base-url 프로퍼티) */
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * 비밀번호 재설정을 요청한다.
     *
     * 보안상 이유로, 이메일이 존재하지 않아도 예외를 던지지 않는다.
     * (이메일 존재 여부를 노출하면 계정 열거 공격(account enumeration)에 악용될 수 있다.)
     *
     * @param email 재설정을 요청한 이메일 주소
     */
    @Transactional
    public void requestReset(String email) {
        // 이메일로 사용자 조회 — 없으면 아무 동작도 하지 않는다 (보안)
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.info("비밀번호 재설정 요청: 존재하지 않는 이메일 — {}", email);
            return;
        }

        User user = userOpt.get();

        // 기존 토큰 삭제 (재발급 전 정리)
        tokenRepository.deleteByUser(user);

        // UUID 토큰 생성 후 저장
        String tokenValue = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(TOKEN_EXPIRY_MINUTES);
        tokenRepository.save(new PasswordResetToken(tokenValue, user, expiresAt));

        // 재설정 링크 조립
        String resetLink = baseUrl + "/reset-password?token=" + tokenValue;
        sendResetEmail(email, resetLink);
    }

    /**
     * 토큰을 검증하고 비밀번호를 변경한다.
     *
     * @param token          재설정 링크에 포함된 토큰
     * @param newPassword    새 비밀번호
     * @param confirmPassword 새 비밀번호 확인
     * @throws IllegalArgumentException 토큰이 유효하지 않거나 비밀번호가 일치하지 않을 때
     */
    @Transactional
    public void resetPassword(String token, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("새 비밀번호 확인이 일치하지 않습니다.");
        }
        if (newPassword.length() < 6) {
            throw new IllegalArgumentException("비밀번호는 6자 이상이어야 합니다.");
        }

        // 토큰 조회
        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 재설정 링크입니다."));

        // 유효성 검사 (만료 또는 이미 사용된 토큰)
        if (!resetToken.isValid()) {
            throw new IllegalArgumentException("만료되었거나 이미 사용된 재설정 링크입니다. 다시 요청해주세요.");
        }

        // 비밀번호 변경 후 토큰 사용 처리
        resetToken.getUser().updatePassword(passwordEncoder.encode(newPassword));
        resetToken.markUsed();
    }

    /* ── private helpers ──────────────────────────────────────── */

    /**
     * 재설정 이메일을 발송한다.
     * JavaMailSender 빈이 없는 경우 콘솔 로그로 링크를 출력한다.
     */
    private void sendResetEmail(String to, String resetLink) {
        if (mailSender.isEmpty()) {
            // 개발 환경: SMTP 미설정 시 링크를 로그로 출력
            log.info("===== [개발용 비밀번호 재설정 링크] =====");
            log.info("수신: {}", to);
            log.info("링크: {}", resetLink);
            log.info("==========================================");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject("[BBS] 비밀번호 재설정 안내");
        message.setText(
                "안녕하세요.\n\n" +
                "아래 링크를 클릭하면 비밀번호를 재설정할 수 있습니다.\n" +
                "링크는 발급 후 " + TOKEN_EXPIRY_MINUTES + "분 동안 유효합니다.\n\n" +
                resetLink + "\n\n" +
                "본인이 요청하지 않았다면 이 메일을 무시해주세요.\n"
        );

        try {
            mailSender.get().send(message);
            log.info("비밀번호 재설정 이메일 발송 완료: {}", to);
        } catch (Exception e) {
            // 발송 실패 시 로그로 링크 출력 후 예외를 삼킨다 (사용자 경험 보호)
            log.error("이메일 발송 실패 (수신: {}): {}", to, e.getMessage());
            log.info("===== [이메일 발송 실패 — 재설정 링크] =====");
            log.info("링크: {}", resetLink);
            log.info("=============================================");
        }
    }
}
