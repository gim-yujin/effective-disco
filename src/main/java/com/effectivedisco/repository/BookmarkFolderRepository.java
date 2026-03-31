package com.effectivedisco.repository;

import com.effectivedisco.domain.BookmarkFolder;
import com.effectivedisco.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 북마크 폴더 저장소.
 */
public interface BookmarkFolderRepository extends JpaRepository<BookmarkFolder, Long> {

    /** 특정 사용자의 폴더 목록 (이름순) */
    List<BookmarkFolder> findByUserOrderByNameAsc(User user);

    /** 특정 사용자·폴더 이름으로 조회 (중복 검사용) */
    Optional<BookmarkFolder> findByUserAndName(User user, String name);

    /** 특정 사용자·폴더 ID로 조회 (권한 검증용) */
    Optional<BookmarkFolder> findByIdAndUser(Long id, User user);
}
