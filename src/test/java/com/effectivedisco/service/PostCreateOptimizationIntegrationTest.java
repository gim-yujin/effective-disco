package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Tag;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class PostCreateOptimizationIntegrationTest {

    @Autowired PostService postService;
    @Autowired UserRepository userRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired TagRepository tagRepository;
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
    void createPost_usesBoundedStatementCountWithBatchTagResolution() {
        User author = userRepository.save(User.builder()
                .username("post-create-opt-author")
                .email("post-create-opt-author@example.com")
                .password("encoded-password")
                .build());

        Board board = boardRepository.save(Board.builder()
                .name("개발게시판")
                .slug("post-create-opt-board")
                .description("테스트 게시판")
                .build());

        tagRepository.save(new Tag("spring"));
        tagRepository.save(new Tag("java"));
        tagRepository.save(new Tag("loadtest"));

        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        PostRequest request = new PostRequest();
        request.setTitle("최적화된 게시글");
        request.setContent("createPost hot path");
        request.setBoardSlug(board.getSlug());
        request.setTagsInput("spring, java, loadtest");

        PostResponse response = postService.createPost(request, author.getUsername());
        entityManager.flush();

        assertThat(response.getAuthor()).isEqualTo(author.getUsername());
        assertThat(response.getBoardSlug()).isEqualTo(board.getSlug());
        assertThat(response.getTags())
                .as("문제 해결 검증: createPost 응답은 저장 직후 연관 LAZY 로딩 없이 정렬된 태그 목록을 반환해야 한다")
                .containsExactly("java", "loadtest", "spring");
        assertThat(statistics.getPrepareStatementCount())
                .as("문제 해결 검증: createPost 는 태그 수만큼 findByName() 를 반복하지 말고 batch lookup 으로 SQL 수를 상수 개수로 묶어야 한다")
                .isLessThanOrEqualTo(8L);
    }
}
