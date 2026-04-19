package com.effectivedisco.service;

import com.effectivedisco.domain.Block;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.RelationAtomicInserter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    private final BlockRepository         blockRepository;
    private final UserLookupService       userLookupService;
    private final RelationAtomicInserter  relationAtomicInserter;

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

        User blocker = userLookupService.findByUsername(blockerUsername);
        User blocked = userLookupService.findByUsername(blockedUsername);

        // 원자적 idempotent 삽입 (PG: ON CONFLICT DO NOTHING, H2: MERGE KEY).
        // blocker row에 FOR UPDATE를 걸던 기존 직렬화 경로를 제거해 동시 toggle 압력 하에서도
        // lock contention 없이 unique 제약만으로 중복을 방지한다.
        relationAtomicInserter.insertBlock(blocker.getId(), blocked.getId(), LocalDateTime.now());
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

        User blocker = userLookupService.findByUsername(blockerUsername);
        User blocked = userLookupService.findByUsername(blockedUsername);

        // deleteByBlockerAndBlocked는 DELETE ... WHERE 단일 문으로 이미 원자적이다.
        // 반복 요청이어도 삭제 결과 0이 되어 최종 상태는 "차단 해제"로 수렴한다.
        blockRepository.deleteByBlockerAndBlocked(blocker, blocked);
    }

    /**
     * blockerUsername이 blockedUsername을 차단 중인지 확인한다.
     * 프로필 페이지에서 차단 버튼 상태를 결정할 때 사용한다.
     *
     * @return 차단 중이면 true
     */
    public boolean isBlocking(String blockerUsername, String blockedUsername) {
        User blocker = userLookupService.findByUsername(blockerUsername);
        User blocked = userLookupService.findByUsername(blockedUsername);
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
        User user = userLookupService.findByUsername(username);
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
        User user = userLookupService.findByUsername(username);
        return blockRepository.findByBlockerOrderByCreatedAtDesc(user);
    }

}
