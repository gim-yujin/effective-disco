package com.effectivedisco.security;

import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserRepository.SecurityUserSnapshot user = userRepository.findSecuritySnapshotByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        // 계정 정지 확인: 정지 상태이면 LockedException 을 던져 로그인을 차단한다
        if (isCurrentlySuspended(user)) {
            String reason = buildSuspensionMessage(user);
            throw new LockedException(reason);
        }

        // DB에 저장된 role("ROLE_USER" 또는 "ROLE_ADMIN")을 그대로 권한으로 사용
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                List.of(new SimpleGrantedAuthority(user.getRole()))
        );
    }

    /**
     * 정지 안내 메시지를 조립한다.
     * 사유와 해제 일자를 포함해 사용자에게 명확한 정보를 제공한다.
     */
    private String buildSuspensionMessage(UserRepository.SecurityUserSnapshot user) {
        StringBuilder msg = new StringBuilder("계정이 정지되었습니다");

        if (user.getSuspensionReason() != null && !user.getSuspensionReason().isBlank()) {
            msg.append(": ").append(user.getSuspensionReason());
        }

        if (user.getSuspendedUntil() != null) {
            // 기간 정지: 해제 일자를 사람이 읽기 쉬운 형태로 표시
            String until = user.getSuspendedUntil()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            msg.append(" (~").append(until).append("까지)");
        } else {
            msg.append(" (영구)");
        }

        return msg.toString();
    }

    private static boolean isCurrentlySuspended(UserRepository.SecurityUserSnapshot user) {
        if (!user.getSuspended()) return false;
        if (user.getSuspendedUntil() == null) return true;
        return java.time.LocalDateTime.now().isBefore(user.getSuspendedUntil());
    }
}
