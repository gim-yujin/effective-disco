package com.effectivedisco.repository;

import com.effectivedisco.domain.Block;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BlockRepository extends JpaRepository<Block, Long> {

    /** blocker가 blocked를 차단 중인지 확인한다. */
    boolean existsByBlockerAndBlocked(User blocker, User blocked);

    /**
     * blocker-blocked 쌍으로 차단 관계를 조회한다.
     * 기존 코드와 테스트에서 차단 상태를 직접 확인할 때 사용한다.
     */
    Optional<Block> findByBlockerAndBlocked(User blocker, User blocked);

    /**
     * 차단 관계를 단일 bulk DELETE로 제거한다.
     *
     * <p>Spring Data의 파생 delete 메서드는 엔티티를 로드한 뒤 {@code em.remove()}로 삭제하므로,
     * 동시 호출에서 두 세션이 같은 row를 로드·삭제할 경우 두 번째 세션이 {@code StaleObjectState}
     * (0 rows affected)로 실패한다. 단일 bulk DELETE는 "없으면 0건 삭제"로 자연스럽게 idempotent하다.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Block b WHERE b.blocker = :blocker AND b.blocked = :blocked")
    long deleteByBlockerAndBlocked(@Param("blocker") User blocker, @Param("blocked") User blocked);

    long countByBlockerAndBlocked(User blocker, User blocked);

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
