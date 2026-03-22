package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.dto.request.BoardCreateRequest;
import com.effectivedisco.dto.response.BoardResponse;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BoardServiceTest {

    @Mock BoardRepository boardRepository;
    @Mock PostRepository  postRepository;

    @InjectMocks BoardService boardService;

    // ── getAllBoards ───────────────────────────────────────

    @Test
    void getAllBoards_returnsMappedListWithPostCount() {
        Board free = makeBoard("자유게시판", "free");
        Board dev  = makeBoard("개발게시판", "dev");

        given(boardRepository.findAllByOrderByNameAsc()).willReturn(List.of(dev, free));
        given(postRepository.countByBoardAndDraftFalse(free)).willReturn(5L);
        given(postRepository.countByBoardAndDraftFalse(dev)).willReturn(3L);

        List<BoardResponse> result = boardService.getAllBoards();

        assertThat(result).hasSize(2);
        // 반환 순서는 findAllByOrderByNameAsc 결과를 따름
        assertThat(result.get(0).getSlug()).isEqualTo("dev");
        assertThat(result.get(0).getPostCount()).isEqualTo(3L);
    }

    // ── getBoard ──────────────────────────────────────────

    @Test
    void getBoard_found_returnsBoard() {
        Board board = makeBoard("자유게시판", "free");
        given(boardRepository.findBySlug("free")).willReturn(Optional.of(board));

        Board result = boardService.getBoard("free");

        assertThat(result.getSlug()).isEqualTo("free");
    }

    @Test
    void getBoard_notFound_throwsIllegalArgumentException() {
        given(boardRepository.findBySlug("unknown")).willReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.getBoard("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 게시판");
    }

    // ── createBoard ───────────────────────────────────────

    @Test
    void createBoard_success_savesAndReturns() {
        BoardCreateRequest req = makeRequest("새게시판", "new-board", "설명");

        given(boardRepository.existsBySlug("new-board")).willReturn(false);
        given(boardRepository.save(any(Board.class)))
                .willAnswer(inv -> inv.getArgument(0));

        BoardResponse result = boardService.createBoard(req);

        assertThat(result.getName()).isEqualTo("새게시판");
        assertThat(result.getSlug()).isEqualTo("new-board");
        verify(boardRepository).save(any(Board.class));
    }

    @Test
    void createBoard_duplicateSlug_throwsIllegalArgumentException() {
        BoardCreateRequest req = makeRequest("이름", "free", null);
        given(boardRepository.existsBySlug("free")).willReturn(true);

        assertThatThrownBy(() -> boardService.createBoard(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 사용 중인 슬러그");
    }

    // ── updateBoard ───────────────────────────────────────

    @Test
    void updateBoard_success_updatesNameAndDescription() {
        Board board = makeBoard("구이름", "free");
        given(boardRepository.findBySlug("free")).willReturn(Optional.of(board));
        given(postRepository.countByBoardAndDraftFalse(board)).willReturn(10L);

        BoardCreateRequest req = makeRequest("새이름", "free", "새설명");
        BoardResponse result = boardService.updateBoard("free", req);

        assertThat(result.getName()).isEqualTo("새이름");
        assertThat(result.getPostCount()).isEqualTo(10L);
    }

    // ── deleteBoard ───────────────────────────────────────

    @Test
    void deleteBoard_success_detachesPostsAndDeletes() {
        Board board = makeBoard("자유게시판", "free");
        given(boardRepository.findBySlug("free")).willReturn(Optional.of(board));

        boardService.deleteBoard("free");

        // 게시물을 미분류로 전환한 뒤 게시판 삭제
        verify(postRepository).detachFromBoard(board);
        verify(boardRepository).delete(board);
    }

    @Test
    void deleteBoard_notFound_throwsIllegalArgumentException() {
        given(boardRepository.findBySlug("gone")).willReturn(Optional.empty());

        assertThatThrownBy(() -> boardService.deleteBoard("gone"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 게시판");
    }

    // ── helpers ───────────────────────────────────────────

    private Board makeBoard(String name, String slug) {
        return Board.builder().name(name).slug(slug).build();
    }

    private BoardCreateRequest makeRequest(String name, String slug, String description) {
        BoardCreateRequest req = new BoardCreateRequest();
        req.setName(name);
        req.setSlug(slug);
        req.setDescription(description);
        return req;
    }
}
