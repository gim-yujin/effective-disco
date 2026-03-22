package com.effectivedisco.repository;

import com.effectivedisco.domain.Follow;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FollowRepository extends JpaRepository<Follow, Long> {

    /** 특정 팔로우 관계가 존재하는지 확인 (팔로우 여부 표시용) */
    boolean existsByFollowerAndFollowing(User follower, User following);

    /** 팔로우 관계 단건 조회 (언팔로우 시 삭제 대상 조회용) */
    Optional<Follow> findByFollowerAndFollowing(User follower, User following);

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
}
