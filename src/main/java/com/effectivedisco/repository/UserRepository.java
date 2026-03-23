package com.effectivedisco.repository;

import com.effectivedisco.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    /**
     * 문제 해결:
     * 같은 사용자가 같은 의도의 쓰기 요청을 동시에 여러 번 보내면
     * "조회 후 삽입" 사이에 경합이 생긴다. 요청 주체 User 행을 잠궈 해당 사용자의
     * 쓰기 요청을 직렬화하면 중복 insert race를 서비스 레이어에서 안전하게 막을 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.username = :username")
    Optional<User> findByUsernameForUpdate(@Param("username") String username);

    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    /** 관리자 패널: 가입일 최신순 전체 사용자 목록 */
    List<User> findAllByOrderByCreatedAtDesc();

    /** 비밀번호 재설정: 이메일로 사용자 조회 */
    Optional<User> findByEmail(String email);

    /** 관리자 초기화: ROLE_ADMIN 계정이 이미 있는지 확인 */
    boolean existsByRole(String role);
}
