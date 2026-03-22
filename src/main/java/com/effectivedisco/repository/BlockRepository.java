package com.effectivedisco.repository;

import com.effectivedisco.domain.Block;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BlockRepository extends JpaRepository<Block, Long> {

    /** blocker가 blocked를 차단 중인지 확인한다. */
    boolean existsByBlockerAndBlocked(User blocker, User blocked);

    /**
     * blocker-blocked 쌍으로 차단 관계를 조회한다.
     * 차단 해제(toggle) 시 삭제할 엔티티를 가져오는 데 사용한다.
     */
    Optional<Block> findByBlockerAndBlocked(User blocker, User blocked);

    /**
     * blocker가 차단한 전체 목록을 최신순으로 반환한다.
     * 차단 목록 페이지 렌더링과 blockedUsernames 집합 구성에 사용한다.
     */
    List<Block> findByBlockerOrderByCreatedAtDesc(User blocker);

    /**
     * blocker가 수행한 모든 차단 관계를 삭제한다.
     * 회원 탈퇴 시 FK 제약 위반 방지용으로 호출한다.
     */
    void deleteAllByBlocker(User blocker);

    /**
     * blocked 사용자와 관련된 모든 차단 관계를 삭제한다.
     * 회원 탈퇴 시 FK 제약 위반 방지용으로 호출한다.
     */
    void deleteAllByBlocked(User blocked);
}
