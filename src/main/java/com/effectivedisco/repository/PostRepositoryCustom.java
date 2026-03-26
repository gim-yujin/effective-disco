package com.effectivedisco.repository;

import com.effectivedisco.domain.Board;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.time.LocalDateTime;

/**
 * PostgreSQL 전용 검색 최적화와 테스트용 fallback 을 함께 담는 커스텀 저장소.
 *
 * 키워드 검색은 `title/content` 와 `author username` 의 특성이 달라
 * 단일 전략으로 묶으면 성능과 검색 의미가 모두 나빠진다.
 * PostgreSQL 에서는 `title/content` 를 FTS 로, `username` 은 substring semantics 를 유지한 채
 * pg_trgm 인덱스가 받쳐주는 LIKE 로 처리한다.
 * H2 테스트 프로필에서는 같은 의미를 유지하는 fallback SQL 로 동작시켜
 * CI 환경에서도 검색 회귀를 계속 검증한다.
 */
public interface PostRepositoryCustom {

    Page<PostRepository.PostListRow> searchPublicPostListRows(String keyword, Pageable pageable);

    Page<PostRepository.PostListRow> searchPublicPostListRowsInBoard(Board board, String keyword, Pageable pageable);

    /**
     * 문제 해결:
     * search slice hot path 는 browse 와 달리 아직 "검색 + 정렬 + author/board projection" 을 한 SQL에서 모두 처리했다.
     * 먼저 id window 만 잘라낸 뒤 작은 row batch 를 읽어야 broad mixed 장시간 soak 에서 search rows drift 를 줄일 수 있다.
     */
    Slice<Long> searchPublicPostIdsSlice(String keyword,
                                         Pageable pageable,
                                         LocalDateTime cursorCreatedAt,
                                         Long cursorId);

    Slice<Long> searchPublicPostIdsInBoardSlice(Board board,
                                                String keyword,
                                                Pageable pageable,
                                                LocalDateTime cursorCreatedAt,
                                                Long cursorId);
}
