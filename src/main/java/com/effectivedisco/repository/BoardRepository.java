package com.effectivedisco.repository;

import com.effectivedisco.domain.Board;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 게시판 레포지토리.
 * Spring Data JPA 파생 쿼리를 사용한다.
 */
public interface BoardRepository extends JpaRepository<Board, Long> {

    /** 슬러그로 게시판 조회 (URL 파라미터 → 게시판 엔티티 변환에 사용) */
    Optional<Board> findBySlug(String slug);

    /** 슬러그 중복 여부 확인 (게시판 생성 시 유효성 검사용) */
    boolean existsBySlug(String slug);

    /** 전체 게시판을 이름 오름차순으로 조회 (홈 화면 목록 표시용) */
    List<Board> findAllByOrderByNameAsc();
}
