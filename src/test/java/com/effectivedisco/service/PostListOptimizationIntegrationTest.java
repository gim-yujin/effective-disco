package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostImage;
import com.effectivedisco.domain.Tag;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class PostListOptimizationIntegrationTest {

    @Autowired PostService postService;
    @Autowired UserRepository userRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired TagRepository tagRepository;
    @Autowired PostRepository postRepository;
    @Autowired EntityManager entityManager;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @Test
    void getPosts_preloadsCurrentPageRelationsWithBoundedStatementCount() {
        String tagPrefix = "list-opt";
        Board board = boardRepository.save(Board.builder()
                .name("자유게시판")
                .slug("free-" + tagPrefix)
                .description("테스트 게시판")
                .build());

        Tag java = tagRepository.save(new Tag("java-" + tagPrefix));
        Tag spring = tagRepository.save(new Tag("spring-" + tagPrefix));

        for (int i = 0; i < 10; i++) {
            User author = userRepository.save(User.builder()
                    .username("author" + i)
                    .email("author" + i + "@example.com")
                    .password("encoded-password")
                    .build());

            Post post = Post.builder()
                    .title("post-" + i)
                    .content("content-" + i)
                    .author(author)
                    .board(board)
                    .build();
            post.getTags().add(java);
            post.getTags().add(spring);
            post.addImage(new PostImage(post, "/images/" + i + "-1.jpg", 0));
            post.addImage(new PostImage(post, "/images/" + i + "-2.jpg", 1));
            postRepository.save(post);
        }

        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        Page<PostResponse> result = postService.getPosts(0, 10, null, null, null, "latest");

        assertThat(result.getContent()).hasSize(10);
        assertThat(result.getContent()).allSatisfy(post -> {
            assertThat(post.getAuthor()).startsWith("author");
            assertThat(post.getBoardSlug()).isEqualTo("free-" + tagPrefix);
            assertThat(post.getTags()).containsExactly("java-" + tagPrefix, "spring-" + tagPrefix);
            assertThat(post.getImageUrls()).hasSize(2);
        });
        assertThat(statistics.getPrepareStatementCount())
                .as("문제 해결 검증: post.list 는 게시물 수만큼 LAZY select 를 늘리지 말고 현재 페이지 연관 데이터를 상수 개수 SQL 로 preload 해야 한다")
                .isLessThanOrEqualTo(6L);
    }
}
