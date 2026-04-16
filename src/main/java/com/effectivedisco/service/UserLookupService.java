package com.effectivedisco.service;

import com.effectivedisco.domain.User;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 조회 전용 서비스.
 *
 * 여러 서비스에서 반복되던 {@code findUser()} / {@code findUserForUpdate()} 패턴을
 * 한 곳으로 통합하여 예외 메시지와 조회 로직의 일관성을 보장한다.
 */
@Service
@RequiredArgsConstructor
public class UserLookupService {

    private final UserRepository userRepository;

    /**
     * username으로 사용자를 조회한다.
     *
     * @throws UsernameNotFoundException 사용자를 찾을 수 없을 때
     */
    @Transactional(readOnly = true)
    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "사용자를 찾을 수 없습니다: " + username));
    }

    /**
     * username으로 사용자를 조회하면서 행을 잠근다 (PESSIMISTIC_WRITE).
     * bookmark/follow/block toggle 등 "조회 후 삽입" race를 방지할 때 사용한다.
     *
     * @throws UsernameNotFoundException 사용자를 찾을 수 없을 때
     */
    @Transactional
    public User findByUsernameForUpdate(String username) {
        return userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "사용자를 찾을 수 없습니다: " + username));
    }
}
