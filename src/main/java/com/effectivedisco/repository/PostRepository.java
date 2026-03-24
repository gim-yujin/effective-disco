package com.effectivedisco.repository;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 게시물 레포지토리.
 *
 * 공개 목록 조회 메서드는 모두 draft = false 조건을 포함한다.
 * 초안은 작성자 전용 메서드(findByAuthorAndDraftTrue*)를 통해서만 접근한다.
 */
public interface PostRepository extends JpaRepository<Post, Long>, PostRepositoryCustom {

    interface CommentNotificationTarget {
        Long getId();
        String getAuthorUsername();
    }

    interface PostListRow {
        Long getId();
        String getTitle();
        String getContent();
        LocalDateTime getCreatedAt();
        LocalDateTime getUpdatedAt();
        int getCommentCount();
        long getLikeCount();
        int getViewCount();
        boolean isPinned();
        boolean isDraft();
        String getLegacyImageUrl();
        String getAuthorUsername();
        String getBoardName();
        String getBoardSlug();
    }

    interface PostTagRow {
        Long getPostId();
        String getTagName();
    }

    interface PostImageRow {
        Long getPostId();
        String getImageUrl();
    }

    // ══════════════════════════════════════════════════════
    // 목록/검색 projection 쿼리
    // ══════════════════════════════════════════════════════

    /**
     * 문제 해결:
     * post.list hot path 는 목록 본문에 필요한 컬럼만 읽으면 되는데, EntityGraph 로 author 전체 엔티티를 끌고 오면서
     * users 컬럼 fan-out 과 entity hydration 비용이 커졌다. 목록 전용 projection 으로 본문 select 폭을 줄인다.
     */
    @Query(value = """
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.draft = false
            ORDER BY p.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p
            WHERE p.draft = false
            """)
    Page<PostListRow> findPublicPostListRowsOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 문제 해결:
     * 태그 검색도 Page count 에서 JOIN post_tags fan-out 이 커진다. 본문/카운트 모두 EXISTS 기반으로 태그 존재만 확인한다.
     */
    @Query(value = """
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.draft = false
              AND EXISTS (
                SELECT 1
                FROM p.tags t
                WHERE t.name = :tagName
              )
            ORDER BY p.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p
            WHERE p.draft = false
              AND EXISTS (
                SELECT 1
                FROM p.tags t
                WHERE t.name = :tagName
              )
            """)
    Page<PostListRow> findPublicPostListRowsByTagName(@Param("tagName") String tagName, Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.draft = false
              AND EXISTS (
                SELECT 1
                FROM p.tags t
                WHERE t.name = :tagName
              )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByTagName(@Param("tagName") String tagName, Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.draft = false
              AND EXISTS (
                SELECT 1
                FROM p.tags t
                WHERE t.name = :tagName
              )
              AND (
                p.createdAt < :cursorCreatedAt
                OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId)
              )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByTagNameAndCreatedAtBefore(@Param("tagName") String tagName,
                                                                         @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                                                         @Param("cursorId") Long cursorId,
                                                                         Pageable pageable);

    @Query(value = """
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
            ORDER BY p.createdAt DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p
            WHERE p.board = :board
              AND p.draft = false
            """)
    Page<PostListRow> findPublicPostListRowsByBoardOrderByCreatedAtDesc(@Param("board") Board board, Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardOrderByCreatedAtDesc(@Param("board") Board board, Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
              AND a.username NOT IN :blockedUsernames
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardOrderByCreatedAtDescAndAuthorUsernameNotIn(
            @Param("board") Board board,
            @Param("blockedUsernames") List<String> blockedUsernames,
            Pageable pageable);

    /**
     * 문제 해결:
     * 게시판 최신 목록 무한 스크롤은 offset/page 기반 Page 를 계속 넘기면 count(*)를 매번 치고,
     * 새 글 유입 시 중복/누락도 생긴다. createdAt+id seek cursor 로 다음 batch 만 잘라 읽는다.
     * 또한 목록에서 본문 전체를 반복 전송할 필요가 없으므로 content select 폭도 비워 row width 를 줄인다.
     */
    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
              AND (
                p.createdAt < :cursorCreatedAt
                OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId)
              )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardAndCreatedAtBefore(@Param("board") Board board,
                                                                       @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                                                       @Param("cursorId") Long cursorId,
                                                                       Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
              AND a.username NOT IN :blockedUsernames
              AND (
                p.createdAt < :cursorCreatedAt
                OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId)
              )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardAndCreatedAtBeforeAndAuthorUsernameNotIn(
            @Param("board") Board board,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            @Param("blockedUsernames") List<String> blockedUsernames,
            Pageable pageable);

    @Query(value = """
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
              AND EXISTS (
                SELECT 1
                FROM p.tags t
                WHERE t.name = :tagName
              )
            ORDER BY p.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p
            WHERE p.board = :board
              AND p.draft = false
              AND EXISTS (
                SELECT 1
                FROM p.tags t
                WHERE t.name = :tagName
              )
            """)
    Page<PostListRow> findPublicPostListRowsByBoardAndTagName(@Param("board") Board board,
                                                              @Param("tagName") String tagName,
                                                              Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
              AND EXISTS (
                SELECT 1
                FROM p.tags t
                WHERE t.name = :tagName
              )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardAndTagName(@Param("board") Board board,
                                                               @Param("tagName") String tagName,
                                                               Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
              AND EXISTS (
                SELECT 1
                FROM p.tags t
                WHERE t.name = :tagName
              )
              AND (
                p.createdAt < :cursorCreatedAt
                OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId)
              )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardAndTagNameAndCreatedAtBefore(@Param("board") Board board,
                                                                                  @Param("tagName") String tagName,
                                                                                  @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                                                                  @Param("cursorId") Long cursorId,
                                                                                  Pageable pageable);

    @Query(value = """
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.draft = false
            ORDER BY p.likeCount DESC, p.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p
            WHERE p.draft = false
            """)
    Page<PostListRow> findAllPostListRowsOrderByLikeCountDesc(Pageable pageable);

    @Query(value = """
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.draft = false
            ORDER BY p.commentCount DESC, p.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p
            WHERE p.draft = false
            """)
    Page<PostListRow> findAllPostListRowsOrderByCommentCountDesc(Pageable pageable);

    @Query(value = """
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
            ORDER BY p.likeCount DESC, p.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p
            WHERE p.board = :board
              AND p.draft = false
            """)
    Page<PostListRow> findPostListRowsByBoardOrderByLikeCountDesc(@Param("board") Board board, Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
            ORDER BY p.likeCount DESC, p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardOrderByLikeCountDesc(@Param("board") Board board, Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
              AND (
                p.likeCount < :cursorSortValue
                OR (
                    p.likeCount = :cursorSortValue
                    AND (
                        p.createdAt < :cursorCreatedAt
                        OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId)
                    )
                )
              )
            ORDER BY p.likeCount DESC, p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardAndLikeCountAfter(@Param("board") Board board,
                                                                      @Param("cursorSortValue") Long cursorSortValue,
                                                                      @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                                                      @Param("cursorId") Long cursorId,
                                                                      Pageable pageable);

    @Query(value = """
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
            ORDER BY p.commentCount DESC, p.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(p)
            FROM Post p
            WHERE p.board = :board
              AND p.draft = false
            """)
    Page<PostListRow> findPostListRowsByBoardOrderByCommentCountDesc(@Param("board") Board board, Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
            ORDER BY p.commentCount DESC, p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardOrderByCommentCountDesc(@Param("board") Board board, Pageable pageable);

    @Query("""
            SELECT
                p.id AS id,
                p.title AS title,
                '' AS content,
                p.createdAt AS createdAt,
                p.updatedAt AS updatedAt,
                p.commentCount AS commentCount,
                p.likeCount AS likeCount,
                p.viewCount AS viewCount,
                p.pinned AS pinned,
                p.draft AS draft,
                p.imageUrl AS legacyImageUrl,
                a.username AS authorUsername,
                b.name AS boardName,
                b.slug AS boardSlug
            FROM Post p
            JOIN p.author a
            LEFT JOIN p.board b
            WHERE p.board = :board
              AND p.draft = false
              AND (
                p.commentCount < :cursorSortValue
                OR (
                    p.commentCount = :cursorSortValue
                    AND (
                        p.createdAt < :cursorCreatedAt
                        OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId)
                    )
                )
              )
            ORDER BY p.commentCount DESC, p.createdAt DESC, p.id DESC
            """)
    Slice<PostListRow> findScrollPostListRowsByBoardAndCommentCountAfter(@Param("board") Board board,
                                                                         @Param("cursorSortValue") Long cursorSortValue,
                                                                         @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                                                         @Param("cursorId") Long cursorId,
                                                                         Pageable pageable);

    /**
     * 문제 해결:
     * 목록 DTO 는 태그/이미지 컬렉션이 필요하지만, 본문 select 에 collection join 을 섞으면 페이지네이션이 깨진다.
     * 현재 페이지 ID만 대상으로 가벼운 row projection 을 한 번씩 추가 조회해 collection fan-out 을 상수 개수 쿼리로 묶는다.
     */
    @Query("""
            SELECT p.id AS postId, t.name AS tagName
            FROM Post p
            JOIN p.tags t
            WHERE p.id IN :postIds
            ORDER BY p.id ASC, t.name ASC
            """)
    List<PostTagRow> findTagRowsByPostIdIn(@Param("postIds") List<Long> postIds);

    @Query("""
            SELECT p.id AS postId, i.imageUrl AS imageUrl
            FROM Post p
            JOIN p.images i
            WHERE p.id IN :postIds
            ORDER BY p.id ASC, i.sortOrder ASC
            """)
    List<PostImageRow> findImageRowsByPostIdIn(@Param("postIds") List<Long> postIds);

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
