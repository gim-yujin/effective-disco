package com.effectivedisco.repository;

import com.effectivedisco.domain.Follow;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FollowRepository extends JpaRepository<Follow, Long> {

    /** 특정 팔로우 관계가 존재하는지 확인 (팔로우 여부 표시용) */
    boolean existsByFollowerAndFollowing(User follower, User following);

    /** 팔로우 관계 단건 조회 (언팔로우 시 삭제 대상 조회용) */
    Optional<Follow> findByFollowerAndFollowing(User follower, User following);

    /**
     * 팔로우 관계를 단일 bulk DELETE로 제거한다.
     *
     * <p>파생 delete는 엔티티 로드 → {@code em.remove()} 순서로 동작해 동시 호출 시
     * 두 번째 호출에서 {@code StaleObjectState} 가 발생한다. bulk DELETE는 0건/1건 결과 모두
     * 정상 처리되므로 lock 없이 idempotent 삭제가 가능하다.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Follow f WHERE f.follower = :follower AND f.following = :following")
    long deleteByFollowerAndFollowing(@Param("follower") User follower, @Param("following") User following);

    long countByFollowerAndFollowing(User follower, User following);

    /** 특정 사용자의 팔로워 수 (내가 팔로우를 받은 횟수) */
    long countByFollowing(User following);

    /** 특정 사용자의 팔로잉 수 (내가 팔로우하는 사람 수) */
    long countByFollower(User follower);

    /**
     * 특정 사용자가 팔로우하는 사용자 목록.
     * 피드 쿼리에서 게시물 저자를 필터링할 때 사용한다.
     */
    @Query("SELECT f.following FROM Follow f WHERE f.follower = :user")
    List<User> findFollowingUsers(@Param("user") User user);

    /**
     * 특정 사용자를 팔로우하는 사람(팔로워) 목록을 최신 팔로우 순으로 반환한다.
     * 팔로워 목록 페이지(/users/{username}/followers) 표시에 사용한다.
     */
    @Query("SELECT f.follower FROM Follow f WHERE f.following = :user ORDER BY f.id DESC")
    List<User> findFollowers(@Param("user") User user);

    /**
     * 특정 사용자가 팔로우하는 사람(팔로잉) 목록을 최신 팔로우 순으로 반환한다.
     * 팔로잉 목록 페이지(/users/{username}/following) 표시에 사용한다.
     */
    @Query("SELECT f.following FROM Follow f WHERE f.follower = :user ORDER BY f.id DESC")
    List<User> findFollowings(@Param("user") User user);
}
