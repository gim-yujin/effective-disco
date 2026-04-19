package com.effectivedisco.repository;

import com.effectivedisco.domain.Bookmark;
import com.effectivedisco.domain.BookmarkFolder;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    boolean existsByUserAndPost(User user, Post post);

    /**
     * 북마크를 단일 bulk DELETE로 제거한다.
     *
     * <p>파생 delete는 엔티티 로드 → {@code em.remove()} 순서로 동작해 동시 호출 시
     * 두 번째 호출에서 {@code StaleObjectState} 가 발생한다. bulk DELETE는 0건/1건 결과 모두
     * 정상 처리되므로 lock 없이 idempotent 삭제가 가능하다.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Bookmark b WHERE b.user = :user AND b.post = :post")
    long deleteByUserAndPost(@Param("user") User user, @Param("post") Post post);

    long countByUserAndPost(User user, Post post);

    /** 북마크 목록 (최신순) */
    List<Bookmark> findByUserOrderByCreatedAtDesc(User user);

    /** 특정 폴더의 북마크 목록 (최신순) */
    List<Bookmark> findByUserAndFolderOrderByCreatedAtDesc(User user, BookmarkFolder folder);

    /** 미분류(폴더 없음) 북마크 목록 (최신순) */
    List<Bookmark> findByUserAndFolderIsNullOrderByCreatedAtDesc(User user);

    /** 사용자·게시물로 북마크 엔티티 조회 (폴더 이동 등에 사용) */
    Optional<Bookmark> findByUserAndPost(User user, Post post);

    /** 폴더 삭제 시 해당 폴더의 북마크를 미분류로 초기화 */
    @Modifying
    @Query("UPDATE Bookmark b SET b.folder = null WHERE b.folder = :folder")
    int clearFolder(@Param("folder") BookmarkFolder folder);
}
