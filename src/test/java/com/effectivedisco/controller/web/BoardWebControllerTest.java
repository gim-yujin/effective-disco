package com.effectivedisco.controller.web;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 웹 게시물 폼 컨트롤러(BoardWebController) 통합 테스트.
 *
 * 세션 기반 인증을 사용하는 웹 FilterChain(Order 2)에 대한 HTTP 수준 검증을 담당한다.
 * 주요 검증 대상: 초안 생성·목록·발행, 미인증 접근 차단.
 *
 * 인증: SecurityMockMvcRequestPostProcessors.user()로 MockUser를 주입한다.
 *       서비스 계층이 userRepository.findByUsername()을 호출하므로
 *       동일한 username의 User 엔티티를 반드시 DB에 저장해야 한다.
 * CSRF: 웹 FilterChain은 CSRF 보호가 활성화되어 있으므로
 *       모든 POST 요청에 .with(csrf())를 추가해야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BoardWebControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository        userRepository;
    @Autowired PostRepository        postRepository;
    @Autowired BoardRepository       boardRepository;
    @Autowired CommentRepository     commentRepository;
    @Autowired PasswordEncoder       passwordEncoder;

    MockMvc mockMvc;
    User testUser;
    User otherUser;
    Board board;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        postRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .username("testuser").email("test@test.com")
                .password(passwordEncoder.encode("pass")).build());
        otherUser = userRepository.save(User.builder()
                .username("otheruser").email("other@test.com")
                .password(passwordEncoder.encode("pass")).build());

        // BoardDataInitializer가 생성하는 free/dev/qna/notice와 충돌하지 않는 슬러그 사용
        board = boardRepository.save(
                Board.builder().name("테스트게시판").slug("testboard").build());
    }

    // ── 게시물 작성 (초안) ────────────────────────────────────

    /**
     * 인증된 사용자가 draft=true로 폼을 제출하면 초안이 저장되고
     * 생성된 게시물 상세 페이지(/posts/{id})로 리다이렉트되어야 한다.
     */
    @Test
    void createPost_asDraft_redirectsToCreatedPost() throws Exception {
        // enctype="multipart/form-data" 폼 제출 시뮬레이션 (파일 없이 텍스트 파라미터만)
        mockMvc.perform(multipart("/boards/{slug}/posts", "testboard")
                        .with(csrf())
                        .with(user("testuser"))
                        .param("title", "임시 제목")
                        .param("content", "임시 내용")
                        .param("boardSlug", "testboard")
                        .param("draft", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/posts/*"));
    }

    /**
     * 인증되지 않은 사용자가 게시물 작성을 시도하면
     * 로그인 페이지로 리다이렉트되어야 한다.
     */
    @Test
    void createPost_withoutAuth_redirectsToLogin() throws Exception {
        mockMvc.perform(multipart("/boards/{slug}/posts", "testboard")
                        .with(csrf())
                        .param("title", "제목")
                        .param("content", "내용")
                        .param("boardSlug", "testboard")
                        .param("draft", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ── 초안 목록 ─────────────────────────────────────────────

    /**
     * 인증된 사용자는 GET /drafts에서 200 응답을 받아야 한다.
     */
    @Test
    void draftList_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/drafts")
                        .with(user("testuser")))
                .andExpect(status().isOk());
    }

    /**
     * 미인증 사용자가 GET /drafts에 접근하면 로그인 페이지로 리다이렉트되어야 한다.
     * SecurityConfig: /drafts/** → authenticated()
     */
    @Test
    void draftList_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/drafts"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ── 초안 발행 ─────────────────────────────────────────────

    /**
     * 작성자가 POST /drafts/{id}/publish를 호출하면 초안이 공개되고
     * 게시물 상세 페이지로 리다이렉트되어야 한다.
     */
    @Test
    void publishDraft_byOwner_redirectsToPost() throws Exception {
        Post draft = Post.builder()
                .title("미공개 초안").content("내용").author(testUser).board(board).build();
        draft.saveDraft();
        draft = postRepository.save(draft);

        mockMvc.perform(post("/drafts/{id}/publish", draft.getId())
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + draft.getId()));
    }

    /**
     * 작성자가 아닌 사용자가 초안 발행을 시도하면 403 Forbidden이 반환되어야 한다.
     * GlobalExceptionHandler가 AccessDeniedException을 403으로 변환한다.
     */
    @Test
    void publishDraft_byNonOwner_returns403() throws Exception {
        Post draft = Post.builder()
                .title("미공개 초안").content("내용").author(testUser).board(board).build();
        draft.saveDraft();
        draft = postRepository.save(draft);

        mockMvc.perform(post("/drafts/{id}/publish", draft.getId())
                        .with(csrf())
                        .with(user("otheruser")))
                .andExpect(status().isForbidden());
    }

    /**
     * 미인증 상태에서 초안 발행을 시도하면 로그인 페이지로 리다이렉트되어야 한다.
     */
    @Test
    void publishDraft_withoutAuth_redirectsToLogin() throws Exception {
        Post draft = Post.builder()
                .title("미공개 초안").content("내용").author(testUser).board(board).build();
        draft.saveDraft();
        draft = postRepository.save(draft);

        mockMvc.perform(post("/drafts/{id}/publish", draft.getId())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ── 홈 / 게시판 목록 ──────────────────────────────────────

    /**
     * GET / 는 인증 없이도 200을 반환해야 한다.
     * 모델에 boards, popularTags 속성이 포함되고 뷰는 "boards/index" 여야 한다.
     */
    @Test
    void homePage_returns200() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("boards", "popularTags"))
                .andExpect(view().name("boards/index"));
    }

    /**
     * GET /boards/{slug} 는 인증 없이도 200을 반환해야 한다.
     * 모델에 posts, board 속성이 포함되고 뷰는 "boards/list" 여야 한다.
     */
    @Test
    void boardPostList_returns200() throws Exception {
        mockMvc.perform(get("/boards/{slug}", "testboard"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("posts", "board"))
                .andExpect(view().name("boards/list"));
    }

    // ── 게시물 상세 ───────────────────────────────────────────

    /**
     * 공개 게시물 상세 조회는 인증 없이도 200을 반환해야 한다.
     * 모델에 post, comments 속성이 포함되고 뷰는 "post/detail" 여야 한다.
     */
    @Test
    void postDetail_public_returns200() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("공개 게시물").content("내용").author(testUser).board(board).build());

        mockMvc.perform(get("/posts/{id}", post.getId()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("post", "comments"))
                .andExpect(view().name("post/detail"));
    }

    // ── 게시물 수정 폼 ────────────────────────────────────────

    /**
     * 작성자가 수정 폼에 접근하면 200과 함께 수정 폼을 받아야 한다.
     * 모델에 postRequest 속성이 포함되고 뷰는 "post/form" 여야 한다.
     */
    @Test
    void editPostForm_byOwner_returns200() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("수정할 글").content("내용").author(testUser).board(board).build());

        mockMvc.perform(get("/posts/{id}/edit", post.getId())
                        .with(user("testuser")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("postRequest"))
                .andExpect(view().name("post/form"));
    }

    /**
     * 작성자가 아닌 사용자가 수정 폼에 접근하면 게시물 상세 페이지로 리다이렉트된다.
     * BoardWebController: !post.getAuthor().equals(username) → redirect:/posts/{id}
     */
    @Test
    void editPostForm_byNonOwner_redirectsToPost() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("수정할 글").content("내용").author(testUser).board(board).build());

        mockMvc.perform(get("/posts/{id}/edit", post.getId())
                        .with(user("otheruser")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + post.getId()));
    }

    // ── 게시물 수정 저장 ──────────────────────────────────────

    /**
     * 작성자가 게시물을 수정하면 게시물 상세 페이지로 리다이렉트된다.
     * multipart 로 전송 (enctype="multipart/form-data", 파일 없이 텍스트만).
     */
    @Test
    void updatePost_byOwner_redirectsToPost() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("수정 전 제목").content("수정 전 내용").author(testUser).board(board).build());

        mockMvc.perform(multipart("/posts/{id}/edit", post.getId())
                        .with(csrf())
                        .with(user("testuser"))
                        .param("title",     "수정 후 제목")
                        .param("content",   "수정 후 내용")
                        .param("boardSlug", "testboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + post.getId()));
    }

    // ── 게시물 삭제 ───────────────────────────────────────────

    /**
     * 작성자가 게시물을 삭제하면 해당 게시판 목록으로 리다이렉트된다.
     * boardSlug 가 있으면 /boards/{slug}, 없으면 / 로 이동한다.
     */
    @Test
    void deletePost_byOwner_redirectsToBoard() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("삭제할 글").content("내용").author(testUser).board(board).build());

        mockMvc.perform(post("/posts/{id}/delete", post.getId())
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/testboard"));
    }

    /**
     * 작성자가 아닌 사용자가 게시물 삭제를 시도하면 403 Forbidden 이 반환된다.
     * PostService.checkOwnership → AccessDeniedException →
     * Spring Security 기본 처리 → 403
     */
    @Test
    void deletePost_byNonOwner_returns403() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("삭제 시도할 글").content("내용").author(testUser).board(board).build());

        mockMvc.perform(post("/posts/{id}/delete", post.getId())
                        .with(csrf())
                        .with(user("otheruser")))
                .andExpect(status().isForbidden());
    }

    // ── 좋아요 토글 ───────────────────────────────────────────

    /**
     * 인증된 사용자가 좋아요를 누르면 게시물 상세 페이지로 리다이렉트된다.
     */
    @Test
    void toggleLike_withAuth_redirectsToPost() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("좋아요 테스트").content("내용").author(testUser).board(board).build());

        mockMvc.perform(post("/posts/{id}/like", post.getId())
                        .with(csrf())
                        .with(user("otheruser")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + post.getId()));
    }

    /**
     * 미인증 사용자가 좋아요를 누르면 로그인 페이지로 리다이렉트된다.
     * SecurityConfig: POST /posts/** → anyRequest().authenticated()
     */
    @Test
    void toggleLike_withoutAuth_redirectsToLogin() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("좋아요 테스트").content("내용").author(testUser).board(board).build());

        mockMvc.perform(post("/posts/{id}/like", post.getId())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ── 북마크 토글 ───────────────────────────────────────────

    /**
     * 인증된 사용자가 북마크를 클릭하면 게시물 상세 페이지로 리다이렉트된다.
     */
    @Test
    void toggleBookmark_withAuth_redirectsToPost() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("북마크 테스트").content("내용").author(testUser).board(board).build());

        mockMvc.perform(post("/posts/{id}/bookmark", post.getId())
                        .with(csrf())
                        .with(user("otheruser")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + post.getId()));
    }

    // ── 댓글 CRUD ─────────────────────────────────────────────

    /**
     * 인증된 사용자가 댓글을 작성하면 게시물 댓글 섹션으로 리다이렉트된다.
     */
    @Test
    void addComment_withAuth_redirectsToComments() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("댓글 테스트").content("내용").author(testUser).board(board).build());

        mockMvc.perform(post("/posts/{postId}/comments", post.getId())
                        .with(csrf())
                        .with(user("otheruser"))
                        .param("content", "테스트 댓글 내용입니다."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + post.getId() + "#comments"));
    }

    /**
     * 미인증 사용자가 댓글을 작성하려 하면 로그인 페이지로 리다이렉트된다.
     */
    @Test
    void addComment_withoutAuth_redirectsToLogin() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("댓글 테스트").content("내용").author(testUser).board(board).build());

        mockMvc.perform(post("/posts/{postId}/comments", post.getId())
                        .with(csrf())
                        .param("content", "무단 댓글"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /**
     * 인증된 사용자가 댓글에 대댓글을 작성하면 부모 댓글 앵커로 리다이렉트된다.
     */
    @Test
    void addReply_withAuth_redirectsToComment() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("대댓글 테스트").content("내용").author(testUser).board(board).build());
        Comment parent = commentRepository.save(Comment.builder()
                .content("부모 댓글").post(post).author(testUser).build());

        mockMvc.perform(post("/posts/{postId}/comments/{id}/replies",
                        post.getId(), parent.getId())
                        .with(csrf())
                        .with(user("otheruser"))
                        .param("content", "대댓글 내용입니다."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + post.getId() + "#comment-" + parent.getId()));
    }

    /**
     * 댓글 작성자가 본인 댓글을 삭제하면 댓글 섹션으로 리다이렉트된다.
     */
    @Test
    void deleteComment_byOwner_redirectsToComments() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("댓글 삭제 테스트").content("내용").author(testUser).board(board).build());
        Comment comment = commentRepository.save(Comment.builder()
                .content("삭제할 댓글").post(post).author(testUser).build());

        mockMvc.perform(post("/posts/{postId}/comments/{id}/delete",
                        post.getId(), comment.getId())
                        .with(csrf())
                        .with(user("testuser")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + post.getId() + "#comments"));
    }

    /**
     * 댓글 작성자가 본인 댓글을 수정하면 해당 댓글 앵커로 리다이렉트된다.
     */
    @Test
    void editComment_byOwner_redirectsToComment() throws Exception {
        Post post = postRepository.save(Post.builder()
                .title("댓글 수정 테스트").content("내용").author(testUser).board(board).build());
        Comment comment = commentRepository.save(Comment.builder()
                .content("수정 전 댓글").post(post).author(testUser).build());

        mockMvc.perform(post("/posts/{postId}/comments/{id}/edit",
                        post.getId(), comment.getId())
                        .with(csrf())
                        .with(user("testuser"))
                        .param("content", "수정 후 댓글"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + post.getId() + "#comment-" + comment.getId()));
    }
}
