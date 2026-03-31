package com.effectivedisco.repository;

import com.effectivedisco.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);
    List<Tag> findAllByNameIn(Collection<String> names);
    List<Tag> findAllByOrderByNameAsc();

    /** 접두사로 태그 이름을 검색한다 (자동완성용, 대소문자 무시) */
    List<Tag> findTop10ByNameStartingWithIgnoreCaseOrderByNameAsc(String prefix);
}
