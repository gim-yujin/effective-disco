package com.effectivedisco.controller;

import com.effectivedisco.domain.Block;
import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.BlockRepository;
import com.effectivedisco.repository.BookmarkRepository;
import com.effectivedisco.repository.FollowRepository;
import com.effectivedisco.repository.NotificationRepository;
import com.effectivedisco.repository.PostLikeRepository;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PostControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired PostRepository postRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired PostLikeRepository postLikeRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired BookmarkRepository bookmarkRepository;
    @Autowired FollowRepository followRepository;
    @Autowired BlockRepository blockRepository;
    @Autowired PasswordEncoder passwordEncoder;

    MockMvc mockMvc;
    User testUser;
    User otherUser;
    String testUserToken;
    String otherUserToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // 문제 해결:
        // MockMvc 요청이 만든 관계 row는 다음 테스트 setUp까지 남을 수 있으므로
        // 부모 엔티티(posts/users)를 지우기 전에 연관 테이블을 먼저 비운다.
        postLikeRepository.deleteAll();
        bookmarkRepository.deleteAll();
        blockRepository.deleteAll();
        followRepository.deleteAll();
        notificationRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .username("testuser").email("test@test.com")
                .password(passwordEncoder.encode("pass")).build());
        otherUser = userRepository.save(User.builder()
                .username("otheruser").email("other@test.com")
                .password(passwordEncoder.encode("pass")).build());

        testUserToken  = jwtTokenProvider.generateToken("testuser");
        otherUserToken = jwtTokenProvider.generateToken("otheruser");
    }

    @Test
    void getPosts_noAuth_returns200() throws Exception {
        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void getBoardScrollPosts_latestBoard_returnsCursorBatch() throws Exception {
        Board board = boardRepository.save(Board.builder()
                .name("스크롤 게시판")
                .slug("scroll-board")
                .build());
        postRepository.save(Post.builder().title("post-1").content("content").author(testUser).board(board).build());
        postRepository.save(Post.builder().title("post-2").content("content").author(testUser).board(board).build());
        postRepository.save(Post.builder().title("post-3").content("content").author(testUser).board(board).build());

        mockMvc.perform(get("/api/posts/scroll")
                        .param("boardSlug", "scroll-board")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursorId").isNumber())
                .andExpect(jsonPath("$.nextCursorCreatedAt").isNotEmpty());
    }

    @Test
    void getBoardScrollPosts_authenticated_filtersBlockedAuthors() throws Exception {
        Board board = boardRepository.save(Board.builder()
                .name("차단 스크롤 게시판")
                .slug("blocked-scroll-board")
                .build());
        postRepository.save(Post.builder().title("visible").content("content").author(testUser).board(board).build());
        postRepository.save(Post.builder().title("blocked").content("content").author(otherUser).board(board).build());
        blockRepository.save(new Block(testUser, otherUser));

        mockMvc.perform(get("/api/posts/scroll")
                        .param("boardSlug", "blocked-scroll-board")
                        .param("size", "10")
                        .header("Authorization", "Bearer " + testUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].author").value("testuser"));
    }

    @Test
    void getPostSlice_keywordSearch_returnsCursorBatchWithoutPageMetadata() throws Exception {
        Board board = boardRepository.save(Board.builder()
                .name("검색 게시판")
                .slug("api-search-board")
                .build());
        postRepository.save(Post.builder().title("load post 1").content("content").author(testUser).board(board).build());
        postRepository.save(Post.builder().title("load post 2").content("content").author(testUser).board(board).build());
        postRepository.save(Post.builder().title("other post").content("content").author(testUser).board(board).build());

        mockMvc.perform(get("/api/posts/slice")
                        .param("boardSlug", "api-search-board")
                        .param("keyword", "load")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursorCreatedAt").isNotEmpty())
                .andExpect(jsonPath("$.nextCursorId").isNumber());
    }

    @Test
    void getPostSlice_rankedBrowse_returnsSortCursor() throws Exception {
        Board board = boardRepository.save(Board.builder()
                .name("랭킹 게시판")
                .slug("rank-board")
                .build());
        Post low = Post.builder().title("low").content("content").author(testUser).board(board).build();
        Post high = Post.builder().title("high").content("content").author(testUser).board(board).build();
        postRepository.save(low);
        postRepository.save(high);
        postRepository.incrementLikeCount(high.getId());
        postRepository.incrementLikeCount(high.getId());
        postRepository.incrementLikeCount(low.getId());

        mockMvc.perform(get("/api/posts/slice")
                        .param("boardSlug", "rank-board")
                        .param("sort", "likes")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("high"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursorSortValue").value(2))
                .andExpect(jsonPath("$.nextCursorCreatedAt").isNotEmpty())
                .andExpect(jsonPath("$.nextCursorId").isNumber());
    }

    @Test
    void getPost_success_returns200() throws Exception {
        Post post = postRepository.save(
                Post.builder().title("Hello").content("World").author(testUser).build());

        mockMvc.perform(get("/api/posts/{id}", post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Hello"))
                .andExpect(jsonPath("$.author").value("testuser"));
    }

    @Test
    void createPost_withAuth_returns201() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + testUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postJson("New Post", "Content here")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("New Post"))
                .andExpect(jsonPath("$.author").value("testuser"));
    }

    @Test
    void createPost_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postJson("Title", "Content")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createPost_blankTitle_returns400() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + testUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "", "content", "content"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePost_byOwner_returns200() throws Exception {
        Post post = postRepository.save(
                Post.builder().title("Old").content("Old content").author(testUser).build());

        mockMvc.perform(put("/api/posts/{id}", post.getId())
                        .header("Authorization", "Bearer " + testUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postJson("Updated", "Updated content")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated"));
    }

    @Test
    void updatePost_byNonOwner_returns403() throws Exception {
        Post post = postRepository.save(
                Post.builder().title("Title").content("Content").author(testUser).build());

        mockMvc.perform(put("/api/posts/{id}", post.getId())
                        .header("Authorization", "Bearer " + otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postJson("Hacked", "Hacked content")))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletePost_byOwner_returns204() throws Exception {
        Post post = postRepository.save(
                Post.builder().title("Title").content("Content").author(testUser).build());

        mockMvc.perform(delete("/api/posts/{id}", post.getId())
                        .header("Authorization", "Bearer " + testUserToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePost_byNonOwner_returns403() throws Exception {
        Post post = postRepository.save(
                Post.builder().title("Title").content("Content").author(testUser).build());

        mockMvc.perform(delete("/api/posts/{id}", post.getId())
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletePost_withoutAuth_returns401() throws Exception {
        Post post = postRepository.save(
                Post.builder().title("Title").content("Content").author(testUser).build());

        mockMvc.perform(delete("/api/posts/{id}", post.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void likePost_repeatedRequests_remainLiked() throws Exception {
        Post post = postRepository.save(
                Post.builder().title("Like").content("Content").author(testUser).build());

        mockMvc.perform(post("/api/posts/{id}/like", post.getId())
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(true))
                .andExpect(jsonPath("$.likeCount").value(1));

        mockMvc.perform(post("/api/posts/{id}/like", post.getId())
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(true))
                .andExpect(jsonPath("$.likeCount").value(1));
    }

    @Test
    void unlikePost_repeatedRequests_remainUnliked() throws Exception {
        Post post = postRepository.save(
                Post.builder().title("Like").content("Content").author(testUser).build());

        mockMvc.perform(post("/api/posts/{id}/like", post.getId())
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/posts/{id}/like", post.getId())
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(false))
                .andExpect(jsonPath("$.likeCount").value(0));

        mockMvc.perform(delete("/api/posts/{id}/like", post.getId())
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(false))
                .andExpect(jsonPath("$.likeCount").value(0));
    }

    // ── 초안 가시성 ───────────────────────────────────────────

    /**
     * 초안(draft=true) 게시물은 공개 목록 API(GET /api/posts)에 노출되지 않아야 한다.
     * PostRepository의 모든 공개 조회 메서드에 draft=false 조건이 포함되어 있음을 검증한다.
     */
    @Test
    void getDraftPost_notVisibleInPublicList() throws Exception {
        // 초안 게시물 저장 (draft=true)
        Post draft = Post.builder().title("비공개 초안").content("내용").author(testUser).build();
        draft.saveDraft();
        postRepository.save(draft);

        // 공개 목록에서 초안이 집계되지 않아야 한다 — totalElements == 0
        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * 초안 게시물은 REST API에서도 draft 플래그가 true로 반환되어야 한다.
     * 직접 ID 조회(GET /api/posts/{id})는 접근 제어 없이 허용된다.
     */
    @Test
    void getPost_draftPost_returnsDraftFlagTrue() throws Exception {
        Post draft = Post.builder().title("초안").content("내용").author(testUser).build();
        draft.saveDraft();
        draft = postRepository.save(draft);

        mockMvc.perform(get("/api/posts/{id}", draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").value(true));
    }

    private String postJson(String title, String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of("title", title, "content", content));
    }
}
