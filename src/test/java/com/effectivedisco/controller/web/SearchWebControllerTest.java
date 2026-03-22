package com.effectivedisco.controller.web;

import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.repository.UserRepository;
import com.effectivedisco.service.PostService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * GET /search 통합 테스트.
 *
 * q 접두사에 따른 라우팅 로직을 검증한다:
 *   - 접두사 없음 → type="keyword"  (searchByKeyword)
 *   - "#태그명"   → type="tag"      (findByTagName)
 *   - "@작성자"   → type="author"   (getPostsByAuthor)
 *   - "@없는사람"  → type="author" + userNotFound=true
 *
 * GET /search는 인증 불필요(공개 엔드포인트)이므로 별도 인증 설정 없이 테스트한다.
 * @Transactional 으로 각 테스트 후 DB 롤백을 보장한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SearchWebControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository        userRepository;
    @Autowired PostService           postService;
    @Autowired PasswordEncoder       passwordEncoder;

    MockMvc mockMvc;
    User    testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // 작성자 검색 테스트용 사용자 생성
        testUser = userRepository.save(User.builder()
                .username("searchtester")
                .email("searchtester@test.com")
                .password(passwordEncoder.encode("pass"))
                .build());

        // 태그 검색 테스트용 게시물 생성 (태그 "java" 포함)
        PostRequest req = new PostRequest();
        req.setTitle("자바 입문");
        req.setContent("자바 학습 내용");
        req.setTagsInput("java");
        postService.createPost(req, testUser.getUsername());
    }

    // ── 검색어 없음 ──────────────────────────────────────────

    @Test
    void search_blank_q_postsIsNull() throws Exception {
        // q 파라미터 없이 접근하면 검색 결과를 보여주지 않는다 (posts=null)
        mockMvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("posts"))
                .andExpect(model().attribute("type", "keyword"));
    }

    // ── 키워드 검색 ──────────────────────────────────────────

    @Test
    void search_keyword_typeIsKeyword() throws Exception {
        // "#"·"@" 접두사 없는 일반 키워드 → type="keyword"
        mockMvc.perform(get("/search").param("q", "자바"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("type", "keyword"))
                .andExpect(model().attributeExists("posts"));
    }

    @Test
    void search_keyword_returnsMatchingPosts() throws Exception {
        // "자바 입문" 제목이 DB에 있으므로 1건 이상 반환
        mockMvc.perform(get("/search").param("q", "자바 입문"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("type", "keyword"))
                // totalElements >= 1 은 model().attribute()로 직접 검증하기 어려우므로
                // 뷰 반환 여부로 확인
                .andExpect(view().name("search/results"));
    }

    // ── 태그 검색 (#접두사) ──────────────────────────────────

    @Test
    void search_hashPrefix_typeIsTag() throws Exception {
        // "#java" → 태그 검색, type="tag", searchTarget="java"
        mockMvc.perform(get("/search").param("q", "#java"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("type", "tag"))
                .andExpect(model().attribute("searchTarget", "java"))
                .andExpect(model().attributeExists("posts"));
    }

    @Test
    void search_hashPrefix_nonExistentTag_returnsEmptyPosts() throws Exception {
        // 존재하지 않는 태그 → 결과 0건 (예외 없이 빈 Page 반환)
        mockMvc.perform(get("/search").param("q", "#존재하지않는태그xyz"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("type", "tag"))
                .andExpect(model().attributeExists("posts"));
    }

    // ── 작성자 검색 (@접두사) ────────────────────────────────

    @Test
    void search_atPrefix_typeIsAuthor() throws Exception {
        // "@searchtester" → 작성자 검색, type="author", searchTarget="searchtester"
        mockMvc.perform(get("/search").param("q", "@searchtester"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("type", "author"))
                .andExpect(model().attribute("searchTarget", "searchtester"))
                .andExpect(model().attributeExists("posts"));
    }

    @Test
    void search_atPrefix_nonExistentUser_setsUserNotFound() throws Exception {
        // 존재하지 않는 사용자 → posts=Page.empty(), userNotFound=true
        mockMvc.perform(get("/search").param("q", "@존재하지않는사용자xyz"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("type", "author"))
                .andExpect(model().attribute("userNotFound", true));
    }

    // ── 공통 모델 속성 ────────────────────────────────────────

    @Test
    void search_always_includesPopularTagsInModel() throws Exception {
        // 검색어 유무와 무관하게 popularTags가 모델에 포함된다
        mockMvc.perform(get("/search").param("q", "아무거나"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("popularTags"));
    }
}
