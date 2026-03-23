package com.effectivedisco.repository;

import com.effectivedisco.domain.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 게시판 레포지토리.
 * Spring Data JPA 파생 쿼리를 사용한다.
 */
public interface BoardRepository extends JpaRepository<Board, Long> {

    interface BoardSummary {
        Long getId();
        String getName();
        String getSlug();
    }

    /** 슬러그로 게시판 조회 (URL 파라미터 → 게시판 엔티티 변환에 사용) */
    Optional<Board> findBySlug(String slug);

    /**
     * 문제 해결:
     * createPost 는 게시판 전체 엔티티보다 board id / name / slug 만 있으면 충분하다.
     * 게시물 저장과 응답 생성에 필요한 최소 컬럼만 projection 으로 읽어 hot path 비용을 줄인다.
     */
    @Query("""
            SELECT b.id AS id,
                   b.name AS name,
                   b.slug AS slug
            FROM Board b
            WHERE b.slug = :slug
            """)
    Optional<BoardSummary> findSummaryBySlug(@Param("slug") String slug);

    /** 슬러그 중복 여부 확인 (게시판 생성 시 유효성 검사용) */
    boolean existsBySlug(String slug);

    /** 전체 게시판을 이름 오름차순으로 조회 (홈 화면 목록 표시용) */
    List<Board> findAllByOrderByNameAsc();
}
