package com.effectivedisco.service;

import com.effectivedisco.domain.Block;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 사용자 차단 비즈니스 로직.
 *
 * 차단 방향: blocker → blocked (단방향).
 * 차단한 사람(blocker)의 화면에서만 차단당한 사람의 게시물·댓글이 숨겨진다.
 */
@Service
@RequiredArgsConstructor
public class BlockService {

    private final BlockRepository blockRepository;
    private final UserRepository  userRepository;

    /**
     * 차단 관계를 생성한다.
     * 이미 차단 중이면 그대로 성공 처리한다.
     *
     * @param blockerUsername 차단을 수행하는 사용자명
     * @param blockedUsername 차단 대상 사용자명
     * @throws IllegalArgumentException 자기 자신을 차단하려 할 때
     */
    @Transactional
    public void block(String blockerUsername, String blockedUsername) {
        if (blockerUsername.equals(blockedUsername)) {
            throw new IllegalArgumentException("자기 자신을 차단할 수 없습니다.");
        }

        User blocker = findUserForUpdate(blockerUsername);
        User blocked = findUser(blockedUsername);

        // 문제 해결:
        // 토글은 재시도 시 차단이 풀리는 잘못된 결과를 만들 수 있다.
        // "차단 상태 보장" 연산으로 고정하면 중복 요청은 모두 no-op로 수렴한다.
        if (!blockRepository.existsByBlockerAndBlocked(blocker, blocked)) {
            blockRepository.save(new Block(blocker, blocked));
        }
    }

    /**
     * 차단 관계를 해제한다.
     * 이미 해제된 상태이면 그대로 성공 처리한다.
     */
    @Transactional
    public void unblock(String blockerUsername, String blockedUsername) {
        if (blockerUsername.equals(blockedUsername)) {
            throw new IllegalArgumentException("자기 자신을 차단 해제할 수 없습니다.");
        }

        User blocker = findUserForUpdate(blockerUsername);
        User blocked = findUser(blockedUsername);

        // 문제 해결:
        // delete count 기반 해제는 같은 요청이 여러 번 반복돼도 최종 상태를 false로 유지한다.
        long deleted = blockRepository.deleteByBlockerAndBlocked(blocker, blocked);
        if (deleted == 0) {
            return;
        }
    }

    /**
     * blockerUsername이 blockedUsername을 차단 중인지 확인한다.
     * 프로필 페이지에서 차단 버튼 상태를 결정할 때 사용한다.
     *
     * @return 차단 중이면 true
     */
    public boolean isBlocking(String blockerUsername, String blockedUsername) {
        User blocker = findUser(blockerUsername);
        User blocked = findUser(blockedUsername);
        return blockRepository.existsByBlockerAndBlocked(blocker, blocked);
    }

    /**
     * 현재 사용자가 차단한 사용자명 집합을 반환한다.
     *
     * 게시물 목록, 댓글 목록, 피드 등의 템플릿에서 차단 사용자의 콘텐츠를
     * 숨기기 위해 Set으로 반환한다 (contains() 조회가 O(1)이므로 효율적).
     *
     * @param username 현재 로그인 사용자명
     * @return 차단된 사용자명 집합 (차단 없으면 빈 집합)
     */
    public Set<String> getBlockedUsernames(String username) {
        User user = findUser(username);
        return blockRepository.findByBlockerOrderByCreatedAtDesc(user).stream()
                .map(b -> b.getBlocked().getUsername())
                .collect(Collectors.toSet());
    }

    /**
     * 차단 목록을 Block 엔티티 리스트로 반환한다 (차단 관리 페이지 렌더링용).
     * 최신 차단 순서로 정렬한다.
     *
     * @param username 현재 로그인 사용자명
     * @return Block 엔티티 목록
     */
    public List<Block> getBlockList(String username) {
        User user = findUser(username);
        return blockRepository.findByBlockerOrderByCreatedAtDesc(user);
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "존재하지 않는 사용자입니다: " + username));
    }

    private User findUserForUpdate(String username) {
        return userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "존재하지 않는 사용자입니다: " + username));
    }
}
