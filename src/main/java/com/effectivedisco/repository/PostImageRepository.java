package com.effectivedisco.repository;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostImageRepository extends JpaRepository<PostImage, Long> {

    /**
     * 특정 게시물의 이미지를 정렬 순서(sortOrder) 오름차순으로 조회한다.
     * 갤러리 렌더링 시 업로드 순서를 보존하기 위해 사용한다.
     */
    List<PostImage> findByPostOrderBySortOrderAsc(Post post);

    /** 게시물에 속한 모든 이미지 삭제 (게시물 수정 시 기존 이미지 교체 전 호출) */
    void deleteByPost(Post post);
}
