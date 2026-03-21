package com.effectivedisco.repository;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

    /** 전체 게시물을 최신순으로 페이징 조회 (게시판 미지정 상태의 전체 목록) */
    Page<Post> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 전체 게시물에서 키워드 검색 (제목 OR 내용 OR 작성자).
     * LOWER() 로 대소문자 무시한다.
     */
    @Query("SELECT p FROM Post p WHERE " +
           "LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.author.username) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY p.createdAt DESC")
    Page<Post> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /** 전체 게시물에서 특정 태그로 필터링 */
    @Query("SELECT p FROM Post p JOIN p.tags t WHERE t.name = :tagName ORDER BY p.createdAt DESC")
    Page<Post> findByTagName(@Param("tagName") String tagName, Pageable pageable);

    /* ── 게시판별 쿼리 ─────────────────────────────────────────── */

    /** 특정 게시판의 게시물을 최신순으로 페이징 조회 */
    Page<Post> findByBoardOrderByCreatedAtDesc(Board board, Pageable pageable);

    /**
     * 특정 게시판 안에서 키워드 검색.
     * 제목, 내용, 작성자명에 대해 OR 검색을 수행한다.
     */
    @Query("SELECT p FROM Post p WHERE p.board = :board AND (" +
           "LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.author.username) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY p.createdAt DESC")
    Page<Post> searchByKeywordInBoard(@Param("board") Board board,
                                      @Param("keyword") String keyword,
                                      Pageable pageable);

    /** 특정 게시판 안에서 태그로 필터링 */
    @Query("SELECT p FROM Post p JOIN p.tags t WHERE p.board = :board AND t.name = :tagName " +
           "ORDER BY p.createdAt DESC")
    Page<Post> findByBoardAndTagName(@Param("board") Board board,
                                     @Param("tagName") String tagName,
                                     Pageable pageable);

    /** 게시판별 게시물 수 (홈 화면 게시판 목록의 "N개" 표시용) */
    long countByBoard(Board board);
}
