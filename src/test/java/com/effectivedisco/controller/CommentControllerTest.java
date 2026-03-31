package com.effectivedisco.controller;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.UserRepository;
import com.effectivedisco.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CommentController (REST API) 통합 테스트.
 *
 * 엔드포인트: /api/posts/{postId}/comments/**
 * 인증 방식: JWT Bearer 토큰 (apiFilterChain, stateless)
 *
 * 검증 대상:
 *   GET  /api/posts/{postId}/comments             댓글 목록 (공개)
 *   POST /api/posts/{postId}/comments             댓글 작성 (인증 필요)
 *   POST /api/posts/{postId}/comments/{id}/replies 대댓글 작성 (인증 필요)
 *   PUT  /api/posts/{postId}/comments/{id}        댓글 수정 (본인만)
 *   DELETE /api/posts/{postId}/comments/{id}      댓글 삭제 (본인만)
 *
 * 인증 없음  → 401 Unauthorized
 * 타인 자원  → 403 Forbidden
 * 유효 요청  → 200/201/204
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CommentControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper          objectMapper;
    @Autowired JwtTokenProvider      jwtTokenProvider;
    @Autowired UserRepository        userRepository;
    @Autowired PostRepository        postRepository;
    @Autowired CommentRepository     commentRepository;
    @Autowired PasswordEncoder       passwordEncoder;

    MockMvc mockMvc;

    User   owner;        // 게시물·댓글 작성자
    User   otherUser;    // 타인 (수정/삭제 권한 없음)
    String ownerToken;
    String otherToken;
    Post   post;         // 테스트용 게시물

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        userRepository.deleteAll();

        owner     = userRepository.save(User.builder()
                .username("owner").email("owner@test.com")
                .password(passwordEncoder.encode("pass")).build());
        otherUser = userRepository.save(User.builder()
                .username("other").email("other@test.com")
                .password(passwordEncoder.encode("pass")).build());

        ownerToken = jwtTokenProvider.generateToken("owner");
        otherToken = jwtTokenProvider.generateToken("other");

        post = postRepository.save(
                Post.builder().title("게시물").content("내용").author(owner).build());
    }

    // ── GET 댓글 목록 ─────────────────────────────────────────

    /**
     * GET /api/posts/{postId}/comments 는 인증 없이도 200을 반환해야 한다.
     * 결과는 배열 형태의 JSON 이어야 한다.
     */
    @Test
    void getComments_noAuth_returns200() throws Exception {
        mockMvc.perform(get("/api/posts/{postId}/comments", post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.number").value(0));
    }

    /**
     * 댓글이 있을 때 목록 조회 시 댓글 내용을 포함한 배열이 반환되어야 한다.
     */
    @Test
    void getComments_withExistingComment_returnsCommentList() throws Exception {
        // 댓글 직접 저장 (서비스 우회)
        commentRepository.save(Comment.builder()
                .content("테스트 댓글").post(post).author(owner).build());

        mockMvc.perform(get("/api/posts/{postId}/comments", post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("테스트 댓글"))
                .andExpect(jsonPath("$.content[0].author").value("owner"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ── POST 댓글 작성 ────────────────────────────────────────

    /**
     * JWT 인증된 사용자가 댓글을 작성하면 201 Created 와 함께 댓글 내용이 반환되어야 한다.
     */
    @Test
    void createComment_withAuth_returns201() throws Exception {
        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson("안녕하세요 댓글입니다.")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("안녕하세요 댓글입니다."))
                .andExpect(jsonPath("$.author").value("owner"));
    }

    /**
     * 인증 없이 댓글 작성을 시도하면 401 Unauthorized 가 반환되어야 한다.
     */
    @Test
    void createComment_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson("무단 댓글")))
                .andExpect(status().isUnauthorized());
    }

    /**
     * content 가 빈 문자열이면 @Valid 검증 실패로 400 Bad Request 가 반환되어야 한다.
     */
    @Test
    void createComment_blankContent_returns400() throws Exception {
        mockMvc.perform(post("/api/posts/{postId}/comments", post.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", ""))))
                .andExpect(status().isBadRequest());
    }

    // ── POST 대댓글 작성 ──────────────────────────────────────

    /**
     * 최상위 댓글에 대해 대댓글을 작성하면 201 Created 가 반환되어야 한다.
     * parent 필드가 설정되어 있어야 한다 (isReply=true).
     */
    @Test
    void createReply_validParent_returns201() throws Exception {
        // 부모 댓글 생성
        Comment parent = commentRepository.save(Comment.builder()
                .content("부모 댓글").post(post).author(owner).build());

        mockMvc.perform(post("/api/posts/{postId}/comments/{id}/replies",
                        post.getId(), parent.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson("대댓글 내용")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("대댓글 내용"));
    }

    /**
     * 최대 깊이에 도달한 댓글에 답글을 달려 하면 400 Bad Request 가 반환되어야 한다.
     * 기본 max-depth=2 설정에서 depth=2인 댓글에 답글을 달면 depth=3이 되어 거부된다.
     */
    @Test
    void createReply_onReply_returns400() throws Exception {
        // depth=0 최상위 → depth=1 답글 → depth=2 답글 생성 후, depth=2에 다시 답글 시도
        Comment parent = commentRepository.save(Comment.builder()
                .content("부모 댓글").post(post).author(owner).depth(0).build());
        Comment reply  = commentRepository.save(Comment.builder()
                .content("대댓글 1단계").post(post).author(owner).parent(parent).depth(1).build());
        Comment deepReply = commentRepository.save(Comment.builder()
                .content("대댓글 2단계").post(post).author(owner).parent(reply).depth(2).build());

        mockMvc.perform(post("/api/posts/{postId}/comments/{id}/replies",
                        post.getId(), deepReply.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson("최대 깊이 초과 대댓글")))
                .andExpect(status().isBadRequest());
    }

    // ── PUT 댓글 수정 ─────────────────────────────────────────

    /**
     * 댓글 작성자가 본인 댓글을 수정하면 200 OK 와 수정된 내용이 반환되어야 한다.
     */
    @Test
    void updateComment_byOwner_returns200() throws Exception {
        Comment comment = commentRepository.save(Comment.builder()
                .content("원본 댓글").post(post).author(owner).build());

        mockMvc.perform(put("/api/posts/{postId}/comments/{id}",
                        post.getId(), comment.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson("수정된 댓글")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("수정된 댓글"));
    }

    /**
     * 타인이 댓글을 수정하려 하면 403 Forbidden 이 반환되어야 한다.
     * CommentService 의 checkOwnership 에서 AccessDeniedException 발생.
     */
    @Test
    void updateComment_byNonOwner_returns403() throws Exception {
        Comment comment = commentRepository.save(Comment.builder()
                .content("원본 댓글").post(post).author(owner).build());

        mockMvc.perform(put("/api/posts/{postId}/comments/{id}",
                        post.getId(), comment.getId())
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson("해킹 시도")))
                .andExpect(status().isForbidden());
    }

    /**
     * 인증 없이 댓글 수정을 시도하면 401 Unauthorized 가 반환되어야 한다.
     */
    @Test
    void updateComment_withoutAuth_returns401() throws Exception {
        Comment comment = commentRepository.save(Comment.builder()
                .content("원본 댓글").post(post).author(owner).build());

        mockMvc.perform(put("/api/posts/{postId}/comments/{id}",
                        post.getId(), comment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson("수정 시도")))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE 댓글 삭제 ──────────────────────────────────────

    /**
     * 댓글 작성자가 본인 댓글을 삭제하면 204 No Content 가 반환되어야 한다.
     */
    @Test
    void deleteComment_byOwner_returns204() throws Exception {
        Comment comment = commentRepository.save(Comment.builder()
                .content("삭제할 댓글").post(post).author(owner).build());

        mockMvc.perform(delete("/api/posts/{postId}/comments/{id}",
                        post.getId(), comment.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
    }

    /**
     * 타인이 댓글을 삭제하려 하면 403 Forbidden 이 반환되어야 한다.
     */
    @Test
    void deleteComment_byNonOwner_returns403() throws Exception {
        Comment comment = commentRepository.save(Comment.builder()
                .content("삭제할 댓글").post(post).author(owner).build());

        mockMvc.perform(delete("/api/posts/{postId}/comments/{id}",
                        post.getId(), comment.getId())
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    /**
     * 인증 없이 댓글 삭제를 시도하면 401 Unauthorized 가 반환되어야 한다.
     */
    @Test
    void deleteComment_withoutAuth_returns401() throws Exception {
        Comment comment = commentRepository.save(Comment.builder()
                .content("삭제할 댓글").post(post).author(owner).build());

        mockMvc.perform(delete("/api/posts/{postId}/comments/{id}",
                        post.getId(), comment.getId()))
                .andExpect(status().isUnauthorized());
    }

    // ── 존재하지 않는 게시물 ──────────────────────────────────

    /**
     * 존재하지 않는 게시물 ID에 댓글을 달려 하면 400 Bad Request 가 반환되어야 한다.
     * PostService: "Post not found: {id}" → IllegalArgumentException → GlobalExceptionHandler → 400
     */
    @Test
    void createComment_nonExistentPost_returns400() throws Exception {
        long nonExistentPostId = 999_999L;
        mockMvc.perform(post("/api/posts/{postId}/comments", nonExistentPostId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentJson("없는 게시물에 댓글")))
                .andExpect(status().isBadRequest());
    }

    /* ── 헬퍼 메서드 ──────────────────────────────────────────── */

    /** commentJson - {"content": content} JSON 문자열 생성 */
    private String commentJson(String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of("content", content));
    }
}
