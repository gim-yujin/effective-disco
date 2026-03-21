package com.effectivedisco.controller;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
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

    private String postJson(String title, String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of("title", title, "content", content));
    }
}
