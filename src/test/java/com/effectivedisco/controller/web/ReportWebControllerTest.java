package com.effectivedisco.controller.web;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.Report;
import com.effectivedisco.domain.ReportTargetType;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.ReportRepository;
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
 * ReportWebController 통합 테스트.
 *
 * 검증 대상:
 *   POST /reports/posts/{postId}           게시물 신고 (인증 필요)
 *   POST /reports/comments/{commentId}     댓글 신고 (인증 필요)
 *
 * 성공 시: 원래 페이지로 리다이렉트 + successMsg 플래시
 * 중복 신고: 원래 페이지로 리다이렉트 + errorMsg 플래시
 *
 * ReportService:
 *   - 중복 신고 → IllegalStateException("이미 신고한 항목입니다.")
 *   - 컨트롤러가 catch → errorMsg 플래시
 *
 * 접근 제어:
 *   SecurityConfig: /reports/** → authenticated()
 *   미인증 접근은 /login 으로 리다이렉트되어야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportWebControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository        userRepository;
    @Autowired PostRepository        postRepository;
    @Autowired CommentRepository     commentRepository;
    @Autowired ReportRepository      reportRepository;
    @Autowired PasswordEncoder       passwordEncoder;

    MockMvc mockMvc;
    User    reporter;  // 신고자
    User    postOwner; // 게시물·댓글 작성자
    Post    testPost;  // 신고 대상 게시물

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        reporter  = userRepository.save(User.builder()
                .username("reporter").email("reporter@test.com")
                .password(passwordEncoder.encode("pass")).build());
        postOwner = userRepository.save(User.builder()
                .username("postowner").email("owner@test.com")
                .password(passwordEncoder.encode("pass")).build());

        // 신고 대상 게시물 (board 없이 생성 — board 필드는 nullable)
        testPost = postRepository.save(
                Post.builder().title("신고 테스트 게시물").content("내용").author(postOwner).build());
    }

    // ── 게시물 신고 ────────────────────────────────────────────

    /**
     * 인증된 사용자가 게시물을 신고하면 해당 게시물 페이지로 리다이렉트되고
     * 성공 플래시(successMsg)가 설정되어야 한다.
     */
    @Test
    void reportPost_success_redirectsToPost() throws Exception {
        mockMvc.perform(post("/reports/posts/{postId}", testPost.getId())
                        .with(csrf())
                        .with(user(reporter.getUsername()))
                        .param("reason", "부적절한 내용입니다."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + testPost.getId()))
                .andExpect(flash().attributeExists("successMsg"));
    }

    /**
     * 같은 사용자가 동일 게시물에 중복 신고를 시도하면
     * 게시물 페이지로 리다이렉트되고 오류 플래시(errorMsg)가 설정되어야 한다.
     *
     * ReportService: existsByReporterAndTargetTypeAndTargetId → IllegalStateException
     * ReportWebController: catch (IllegalStateException e) → errorMsg
     */
    @Test
    void reportPost_duplicate_redirectsWithError() throws Exception {
        // 첫 번째 신고를 DB에 직접 저장 (서비스 우회)
        reportRepository.save(Report.builder()
                .reporter(reporter)
                .targetType(ReportTargetType.POST)
                .targetId(testPost.getId())
                .reason("첫 번째 신고")
                .build());

        // 동일 게시물에 중복 신고 → errorMsg
        mockMvc.perform(post("/reports/posts/{postId}", testPost.getId())
                        .with(csrf())
                        .with(user(reporter.getUsername()))
                        .param("reason", "두 번째 중복 신고"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/posts/" + testPost.getId()))
                .andExpect(flash().attributeExists("errorMsg"));
    }

    /**
     * 미인증 사용자가 게시물을 신고하면 /login 으로 리다이렉트된다.
     * CSRF 토큰은 포함하되 인증 정보는 없다.
     */
    @Test
    void reportPost_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(post("/reports/posts/{postId}", testPost.getId())
                        .with(csrf())
                        .param("reason", "부적절한 내용"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ── 댓글 신고 ────────────────────────────────────────────

    /**
     * 인증된 사용자가 댓글을 신고하면 해당 게시물의 댓글 앵커로 리다이렉트된다.
     * /posts/{postId}#comment-{commentId} 형식으로 리다이렉트되어야 한다.
     * 성공 플래시(successMsg)가 설정되어야 한다.
     */
    @Test
    void reportComment_success_redirectsToCommentAnchor() throws Exception {
        Comment comment = commentRepository.save(Comment.builder()
                .content("신고할 댓글 내용").post(testPost).author(postOwner).build());

        mockMvc.perform(post("/reports/comments/{commentId}", comment.getId())
                        .with(csrf())
                        .with(user(reporter.getUsername()))
                        .param("reason", "스팸 댓글")
                        .param("postId", testPost.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/posts/" + testPost.getId() + "#comment-" + comment.getId()))
                .andExpect(flash().attributeExists("successMsg"));
    }

    /**
     * 동일 댓글에 중복 신고를 시도하면 게시물 댓글 앵커로 리다이렉트되고
     * 오류 플래시(errorMsg)가 설정되어야 한다.
     */
    @Test
    void reportComment_duplicate_redirectsWithError() throws Exception {
        Comment comment = commentRepository.save(Comment.builder()
                .content("신고할 댓글 내용").post(testPost).author(postOwner).build());

        // 첫 번째 신고 직접 저장
        reportRepository.save(Report.builder()
                .reporter(reporter)
                .targetType(ReportTargetType.COMMENT)
                .targetId(comment.getId())
                .reason("첫 번째 신고")
                .build());

        // 중복 신고 → errorMsg
        mockMvc.perform(post("/reports/comments/{commentId}", comment.getId())
                        .with(csrf())
                        .with(user(reporter.getUsername()))
                        .param("reason", "두 번째 중복 신고")
                        .param("postId", testPost.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/posts/" + testPost.getId() + "#comment-" + comment.getId()))
                .andExpect(flash().attributeExists("errorMsg"));
    }
}
