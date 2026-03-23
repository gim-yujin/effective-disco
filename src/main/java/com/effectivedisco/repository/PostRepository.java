package com.effectivedisco.repository;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 게시물 레포지토리.
 *
 * 공개 목록 조회 메서드는 모두 draft = false 조건을 포함한다.
 * 초안은 작성자 전용 메서드(findByAuthorAndDraftTrue*)를 통해서만 접근한다.
 */
public interface PostRepository extends JpaRepository<Post, Long> {

    interface CommentNotificationTarget {
        Long getId();
        String getAuthorUsername();
    }

    // ══════════════════════════════════════════════════════
    // 전체 공개 게시물 조회 (draft = false)
    // ══════════════════════════════════════════════════════

    /** 공개 게시물을 최신순으로 페이징 조회 */
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findByDraftFalseOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 전체 공개 게시물에서 키워드 검색 (제목 OR 내용 OR 작성자).
     * LOWER()로 대소문자를 무시한다.
     */
    @Query("SELECT p FROM Post p WHERE p.draft = false AND (" +
           "LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.author.username) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY p.createdAt DESC")
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /** 전체 공개 게시물에서 특정 태그로 필터링 */
    @Query("SELECT p FROM Post p JOIN p.tags t WHERE p.draft = false AND t.name = :tagName " +
           "ORDER BY p.createdAt DESC")
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findByTagName(@Param("tagName") String tagName, Pageable pageable);

    // ══════════════════════════════════════════════════════
    // 게시판별 공개 게시물 조회 (draft = false)
    // ══════════════════════════════════════════════════════

    /** 특정 게시판의 공개 게시물을 최신순으로 페이징 조회 */
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findByBoardAndDraftFalseOrderByCreatedAtDesc(Board board, Pageable pageable);

    /** 특정 게시판의 고정(공지) 공개 게시물 목록 */
    @EntityGraph(attributePaths = {"author", "board"})
    List<Post> findByBoardAndPinnedTrueAndDraftFalseOrderByCreatedAtDesc(Board board);

    /**
     * 특정 게시판 안에서 키워드 검색 (제목 OR 내용 OR 작성자).
     * 공개 게시물만 대상으로 한다.
     */
    @Query("SELECT p FROM Post p WHERE p.board = :board AND p.draft = false AND (" +
           "LOWER(p.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.author.username) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY p.createdAt DESC")
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> searchByKeywordInBoard(@Param("board") Board board,
                                      @Param("keyword") String keyword,
                                      Pageable pageable);

    /** 특정 게시판 안에서 태그로 필터링 (공개 게시물만) */
    @Query("SELECT p FROM Post p JOIN p.tags t " +
           "WHERE p.board = :board AND p.draft = false AND t.name = :tagName " +
           "ORDER BY p.createdAt DESC")
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findByBoardAndTagName(@Param("board") Board board,
                                     @Param("tagName") String tagName,
                                     Pageable pageable);

    /**
     * 게시판별 공개 게시물 수 (홈 화면 게시판 목록의 "N개" 표시용).
     * 초안은 집계에서 제외한다.
     */
    long countByBoardAndDraftFalse(Board board);

    // ══════════════════════════════════════════════════════
    // 프로필 관련 쿼리
    // ══════════════════════════════════════════════════════

    /**
     * 특정 사용자의 공개 게시물을 최신순으로 페이징 조회.
     * 프로필 페이지의 "작성한 게시물" 목록에 사용한다 (초안 제외).
     */
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findByAuthorAndDraftFalseOrderByCreatedAtDesc(User author, Pageable pageable);

    /**
     * 특정 사용자의 공개 게시물 총 수 (프로필 통계 표시용, 초안 제외).
     */
    long countByAuthorAndDraftFalse(User author);

    /**
     * 특정 사용자의 초안(미공개) 게시물을 최신순으로 페이징 조회.
     * 본인의 초안 목록(/drafts) 페이지에 사용한다.
     */
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findByAuthorAndDraftTrueOrderByCreatedAtDesc(User author, Pageable pageable);

    // ══════════════════════════════════════════════════════
    // 팔로우 피드 쿼리 (draft = false)
    // ══════════════════════════════════════════════════════

    /**
     * 팔로우 피드: 지정한 사용자 목록의 공개 게시물을 최신순으로 페이징 조회.
     * 팔로잉 목록이 비어 있으면 호출하지 않아야 한다 (JPQL IN 절에 빈 컬렉션은 오류 발생).
     */
    @Query("SELECT p FROM Post p WHERE p.draft = false AND p.author IN :authors " +
           "ORDER BY p.createdAt DESC")
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findByAuthorInOrderByCreatedAtDesc(
            @Param("authors") List<User> authors, Pageable pageable);

    // ══════════════════════════════════════════════════════
    // 인기 태그
    // ══════════════════════════════════════════════════════

    /**
     * 공개 게시물에 가장 많이 사용된 태그 이름을 빈도 내림차순으로 반환.
     * 홈 화면 및 검색 페이지에 표시한다.
     */
    @Query("SELECT t.name FROM Post p JOIN p.tags t WHERE p.draft = false " +
           "GROUP BY t.name ORDER BY COUNT(p) DESC")
    List<String> findPopularTagNames(Pageable pageable);

    // ══════════════════════════════════════════════════════
    // 정렬 쿼리 (draft = false)
    // ══════════════════════════════════════════════════════

    /** 전체 공개 게시물을 좋아요 수 내림차순으로 페이징 조회 (비정규화 카운트 사용) */
    @Query("SELECT p FROM Post p WHERE p.draft = false ORDER BY p.likeCount DESC, p.createdAt DESC")
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findAllOrderByLikeCountDesc(Pageable pageable);

    /** 전체 공개 게시물을 댓글 수 내림차순으로 페이징 조회 (비정규화 카운트 사용) */
    @Query("SELECT p FROM Post p WHERE p.draft = false ORDER BY p.commentCount DESC, p.createdAt DESC")
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findAllOrderByCommentCountDesc(Pageable pageable);

    /** 특정 게시판의 공개 게시물을 좋아요 수 내림차순으로 페이징 조회 */
    @Query("SELECT p FROM Post p WHERE p.board = :board AND p.draft = false ORDER BY p.likeCount DESC, p.createdAt DESC")
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findByBoardOrderByLikeCountDesc(@Param("board") Board board, Pageable pageable);

    /** 특정 게시판의 공개 게시물을 댓글 수 내림차순으로 페이징 조회 */
    @Query("SELECT p FROM Post p WHERE p.board = :board AND p.draft = false ORDER BY p.commentCount DESC, p.createdAt DESC")
    @EntityGraph(attributePaths = {"author", "board"})
    Page<Post> findByBoardOrderByCommentCountDesc(@Param("board") Board board, Pageable pageable);

    @Query("SELECT DISTINCT p FROM Post p LEFT JOIN FETCH p.tags WHERE p.id IN :postIds")
    List<Post> findAllWithTagsByIdIn(@Param("postIds") List<Long> postIds);

    @Query("SELECT DISTINCT p FROM Post p LEFT JOIN FETCH p.images WHERE p.id IN :postIds")
    List<Post> findAllWithImagesByIdIn(@Param("postIds") List<Long> postIds);

    /**
     * 문제 해결:
     * comment.create hot path 는 댓글 저장 자체보다 "게시물 존재 확인 + 게시물 작성자 username 확인"이 필요하다.
     * 엔티티 전체를 읽지 말고 알림에 필요한 최소 컬럼만 projection 으로 가져와 SELECT fan-out 을 줄인다.
     */
    @Query("SELECT p.id AS id, p.author.username AS authorUsername FROM Post p WHERE p.id = :id")
    Optional<CommentNotificationTarget> findCommentNotificationTargetById(@Param("id") Long id);

    // ══════════════════════════════════════════════════════
    // 원자적 카운트 UPDATE (동시성 안전)
    // ══════════════════════════════════════════════════════

    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :id")
    void incrementLikeCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount - 1 WHERE p.id = :id AND p.likeCount > 0")
    void decrementLikeCount(@Param("id") Long id);

    /** 좋아요 응답에는 stale entity 값 대신 DB의 최신 카운트를 사용한다. */
    @Query("SELECT p.likeCount FROM Post p WHERE p.id = :id")
    long findLikeCountById(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + 1 WHERE p.id = :id")
    void incrementCommentCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount - 1 WHERE p.id = :id AND p.commentCount > 0")
    void decrementCommentCount(@Param("id") Long id);

    // ══════════════════════════════════════════════════════
    // 관리 / 유지보수 쿼리
    // ══════════════════════════════════════════════════════

    /**
     * 게시판 삭제 시 해당 게시판 소속 게시물을 미분류(board = null)로 일괄 전환.
     * 초안 포함 전체 게시물에 적용된다.
     */
    @Modifying
    @Query("UPDATE Post p SET p.board = null WHERE p.board = :board")
    void detachFromBoard(@Param("board") Board board);
}
