package com.effectivedisco.controller.web;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.Report;
import com.effectivedisco.domain.ReportTargetType;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.FollowRepository;
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
 * AdminWebController 통합 테스트.
 *
 * 검증 대상:
 *   GET  /admin                          관리자 대시보드 (ROLE_ADMIN 전용)
 *   GET  /admin/reports?tab=pending      미처리 신고 탭
 *   GET  /admin/reports?tab=resolved     처리 이력 탭
 *   POST /admin/reports/{id}/resolve     신고 처리 완료
 *   POST /admin/reports/{id}/dismiss     신고 기각
 *   POST /admin/users/{id}/toggle-role   권한 토글
 *   POST /admin/posts/{id}/delete        게시물 강제 삭제
 *
 * 접근 제어:
 *   SecurityConfig: /admin/** → hasAuthority("ROLE_ADMIN")
 *   .with(user("testadmin").roles("ADMIN")) 는 ROLE_ADMIN 권한을 부여한다.
 *   일반 사용자(ROLE_USER) 는 403 Forbidden, 미인증은 로그인 리다이렉트.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminWebControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository        userRepository;
    @Autowired PostRepository        postRepository;
    @Autowired ReportRepository      reportRepository;
    @Autowired CommentRepository     commentRepository;
    @Autowired FollowRepository      followRepository;
    @Autowired PasswordEncoder       passwordEncoder;

    MockMvc mockMvc;
    User    adminUser;   // ROLE_ADMIN (username="testadmin" — "admin"은 AdminDataInitializer가 선점)
    User    normalUser;  // ROLE_USER — 신고자 및 관리 대상
    Post    testPost;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        /*
         * AdminDataInitializer가 애플리케이션 시작 시 username="admin" 사용자를 생성한다.
         * 동일 username 으로 저장하면 DataIntegrityViolationException 이 발생하므로
         * 의존 엔티티를 순서대로 삭제한 뒤 테스트 전용 계정으로 초기화한다.
         */
        followRepository.deleteAll();
        reportRepository.deleteAll();
        commentRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = userRepository.save(User.builder()
                .username("testadmin").email("testadmin@test.com")
                .password(passwordEncoder.encode("pass")).build());
        adminUser.promoteToAdmin();
        adminUser = userRepository.save(adminUser);

        normalUser = userRepository.save(User.builder()
                .username("normaluser").email("normal@test.com")
                .password(passwordEncoder.encode("pass")).build());

        testPost = postRepository.save(
                Post.builder().title("테스트 게시물").content("내용").author(normalUser).build());
    }

    // ── 대시보드 접근 제어 ────────────────────────────────────

    /**
     * ROLE_ADMIN 사용자는 GET /admin 에서 200을 받아야 한다.
     */
    @Test
    void dashboard_asAdmin_returns200() throws Exception {
        mockMvc.perform(get("/admin").with(user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/index"));
    }

    /**
     * ROLE_USER 사용자가 /admin 에 접근하면 403 Forbidden이 반환되어야 한다.
     */
    @Test
    void dashboard_asNormalUser_returns403() throws Exception {
        mockMvc.perform(get("/admin").with(user("normaluser").roles("USER")))
                .andExpect(status().isForbidden());
    }

    /**
     * 미인증 사용자가 /admin 에 접근하면 로그인 페이지로 리다이렉트되어야 한다.
     */
    @Test
    void dashboard_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ── 신고 관리 탭 ──────────────────────────────────────────

    /**
     * GET /admin/reports (tab=pending, 기본값) 에서 미처리 신고 목록이 반환되어야 한다.
     * 모델에 tab="pending", pendingCount, reports 속성이 포함되어야 한다.
     */
    @Test
    void reports_pendingTab_showsPendingReports() throws Exception {
        // 신고 1건 추가
        reportRepository.save(Report.builder()
                .reporter(normalUser)
                .targetType(ReportTargetType.POST)
                .targetId(testPost.getId())
                .reason("불량 게시물")
                .build());

        mockMvc.perform(get("/admin/reports")
                        .with(user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("tab", "pending"))
                .andExpect(model().attributeExists("pendingCount", "reports"))
                .andExpect(view().name("admin/reports"));
    }

    /**
     * GET /admin/reports?tab=resolved 에서 처리 이력 탭이 표시되어야 한다.
     * tab 모델 속성이 "resolved" 여야 한다.
     */
    @Test
    void reports_resolvedTab_showsResolvedReports() throws Exception {
        mockMvc.perform(get("/admin/reports")
                        .param("tab", "resolved")
                        .with(user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(model().attribute("tab", "resolved"))
                .andExpect(model().attributeExists("reports"))
                .andExpect(view().name("admin/reports"));
    }

    // ── 신고 처리 ────────────────────────────────────────────

    /**
     * POST /admin/reports/{id}/resolve 후 /admin/reports 로 리다이렉트되어야 한다.
     */
    @Test
    void resolveReport_success_redirectsToReports() throws Exception {
        Report report = reportRepository.save(Report.builder()
                .reporter(normalUser)
                .targetType(ReportTargetType.POST)
                .targetId(testPost.getId())
                .reason("사유")
                .build());

        mockMvc.perform(post("/admin/reports/{id}/resolve", report.getId())
                        .with(csrf())
                        .with(user("testadmin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reports"));
    }

    /**
     * POST /admin/reports/{id}/dismiss 후 /admin/reports 로 리다이렉트되어야 한다.
     */
    @Test
    void dismissReport_success_redirectsToReports() throws Exception {
        Report report = reportRepository.save(Report.builder()
                .reporter(normalUser)
                .targetType(ReportTargetType.POST)
                .targetId(testPost.getId())
                .reason("사유")
                .build());

        mockMvc.perform(post("/admin/reports/{id}/dismiss", report.getId())
                        .with(csrf())
                        .with(user("testadmin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reports"));
    }

    // ── 사용자 권한 토글 ──────────────────────────────────────

    /**
     * POST /admin/users/{id}/toggle-role 으로 일반 사용자를 관리자로 승격하면
     * /admin 으로 리다이렉트되어야 한다.
     */
    @Test
    void toggleRole_normalToAdmin_redirectsToDashboard() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/toggle-role", normalUser.getId())
                        .with(csrf())
                        .with(user("testadmin").roles("ADMIN"))
                        .param("currentUsername", "testadmin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    /**
     * 자기 자신의 권한은 변경할 수 없다.
     * currentUsername 이 대상 username 과 일치하면 변경이 무시되고 /admin 으로 리다이렉트된다.
     * adminUser.getId() 가 대상 ID 이고 currentUsername="testadmin" 이어서 self-toggle 조건이 충족된다.
     */
    @Test
    void toggleRole_selfToggle_isIgnoredAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/toggle-role", adminUser.getId())
                        .with(csrf())
                        .with(user("testadmin").roles("ADMIN"))
                        .param("currentUsername", "testadmin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }

    // ── 게시물 강제 삭제 ──────────────────────────────────────

    /**
     * POST /admin/posts/{id}/delete 로 게시물을 강제 삭제하면 /admin 으로 리다이렉트된다.
     */
    @Test
    void adminDeletePost_success_redirectsToDashboard() throws Exception {
        mockMvc.perform(post("/admin/posts/{id}/delete", testPost.getId())
                        .with(csrf())
                        .with(user("testadmin").roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
    }
}
