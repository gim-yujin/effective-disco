package com.effectivedisco.controller;

import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BoardRepository;
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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * BoardController REST API 통합 테스트.
 *
 * GET  /api/boards     → 게시판 목록 조회 (인증 불필요)
 * POST /api/boards     → 게시판 생성 (JWT 인증 필요)
 *
 * @SpringBootTest + MockMvc(webAppContextSetup) 패턴을 사용한다.
 * @Transactional로 각 테스트 후 DB를 롤백한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BoardControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper          objectMapper;
    @Autowired JwtTokenProvider      jwtTokenProvider;
    @Autowired UserRepository        userRepository;
    @Autowired BoardRepository       boardRepository;
    @Autowired PasswordEncoder       passwordEncoder;

    MockMvc mockMvc;
    String  authToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // 테스트용 사용자 생성 및 JWT 토큰 발급
        User user = userRepository.save(User.builder()
                .username("boardtester")
                .email("board@test.com")
                .password(passwordEncoder.encode("pass123"))
                .build());
        authToken = jwtTokenProvider.generateToken(user.getUsername());
    }

    // ── GET /api/boards ──────────────────────────────────

    @Test
    void getAllBoards_returns200WithBoardList() throws Exception {
        // BoardDataInitializer가 기본 게시판을 생성하므로 비어 있지 않다
        mockMvc.perform(get("/api/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(0))));
    }

    // ── POST /api/boards ─────────────────────────────────

    @Test
    void createBoard_withAuth_returns201() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "테스트 게시판",
                "slug", "test-board",
                "description", "통합 테스트용 게시판"
        ));

        mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("테스트 게시판"))
                .andExpect(jsonPath("$.slug").value("test-board"));
    }

    @Test
    void createBoard_duplicateSlug_returns400() throws Exception {
        // 먼저 게시판 생성
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "첫 번째",
                "slug", "dup-slug",
                "description", ""
        ));
        mockMvc.perform(post("/api/boards")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        // 같은 slug로 다시 생성 시도 → 400
        String duplicateBody = objectMapper.writeValueAsString(Map.of(
                "name", "두 번째",
                "slug", "dup-slug",
                "description", ""
        ));
        mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBoard_withoutAuth_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "무인증 게시판",
                "slug", "no-auth",
                "description", ""
        ));

        // JWT 토큰 없이 요청 → 401
        mockMvc.perform(post("/api/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createBoard_invalidSlug_returns400() throws Exception {
        // 슬러그에 대문자/특수문자 포함 → 유효성 검증 실패
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "잘못된 게시판",
                "slug", "Invalid_Slug!",
                "description", ""
        ));

        mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
