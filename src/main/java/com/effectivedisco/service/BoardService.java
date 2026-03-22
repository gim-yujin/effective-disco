package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.dto.request.BoardCreateRequest;
import com.effectivedisco.dto.response.BoardResponse;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 게시판(Board) 비즈니스 로직.
 * 게시판 목록 조회·생성을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class BoardService {

    private final BoardRepository boardRepository;
    private final PostRepository  postRepository;

    /**
     * 전체 게시판 목록을 이름 오름차순으로 반환한다.
     * 각 게시판의 게시물 수도 함께 제공한다 (홈 화면 표시용).
     */
    public List<BoardResponse> getAllBoards() {
        return boardRepository.findAllByOrderByNameAsc().stream()
                .map(board -> new BoardResponse(board, postRepository.countByBoard(board)))
                .collect(Collectors.toList());
    }

    /**
     * 슬러그로 게시판 엔티티를 조회한다.
     * 존재하지 않으면 IllegalArgumentException을 던진다.
     */
    public Board getBoard(String slug) {
        return boardRepository.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시판입니다: " + slug));
    }

    /**
     * 새 게시판을 생성한다.
     * 슬러그 중복 시 IllegalArgumentException을 던진다.
     */
    @Transactional
    public BoardResponse createBoard(BoardCreateRequest request) {
        if (boardRepository.existsBySlug(request.getSlug())) {
            throw new IllegalArgumentException("이미 사용 중인 슬러그입니다: " + request.getSlug());
        }
        Board board = Board.builder()
                .name(request.getName())
                .slug(request.getSlug())
                .description(request.getDescription())
                .build();
        return new BoardResponse(boardRepository.save(board), 0);
    }

    /**
     * 게시판 정보를 수정한다 (이름·설명만 변경 가능, 슬러그는 변경 불가).
     * 슬러그는 URL 경로로 사용되므로 변경하면 외부 링크가 깨진다.
     */
    @Transactional
    public BoardResponse updateBoard(String slug, BoardCreateRequest request) {
        Board board = boardRepository.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시판입니다: " + slug));
        board.update(request.getName(), request.getDescription());
        return new BoardResponse(board, postRepository.countByBoard(board));
    }

    /**
     * 게시판을 삭제한다.
     * 해당 게시판에 속한 게시물은 미분류(board = null)로 전환되어 보존된다.
     */
    @Transactional
    public void deleteBoard(String slug) {
        Board board = boardRepository.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시판입니다: " + slug));
        // 게시물을 미분류로 전환한 뒤 게시판 삭제
        postRepository.detachFromBoard(board);
        boardRepository.delete(board);
    }
}
