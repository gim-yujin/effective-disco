package com.effectivedisco.service;

import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.CommentRequest;
import com.effectivedisco.dto.response.CommentResponse;
import com.effectivedisco.repository.PostRepository;
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
class CommentCreateOptimizationIntegrationTest {

    @Autowired CommentService commentService;
    @Autowired UserRepository userRepository;
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
    void createComment_usesBoundedStatementCountOnHotPath() {
        User author = User.builder()
                .username("comment-opt-author")
                .email("comment-opt-author@example.com")
                .password("encoded-password")
                .build();
        author.updateProfileImageUrl("/profiles/comment-opt-author.png");
        userRepository.save(author);

        Post post = postRepository.save(Post.builder()
                .title("opt-title")
                .content("opt-content")
                .author(author)
                .build());

        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        CommentRequest request = new CommentRequest();
        request.setContent("최적화된 댓글");

        CommentResponse response = commentService.createComment(post.getId(), request, author.getUsername());
        entityManager.flush();

        assertThat(response.getAuthor()).isEqualTo(author.getUsername());
        assertThat(response.getAuthorProfileImageUrl()).isEqualTo("/profiles/comment-opt-author.png");
        assertThat(response.getReplies())
                .as("문제 해결 검증: freshly-created 댓글 응답은 대댓글 LAZY 로딩 없이 빈 목록으로 즉시 직렬화되어야 한다")
                .isEmpty();
        assertThat(statistics.getPrepareStatementCount())
                .as("문제 해결 검증: comment.create 는 projection/getReference 경로로 SQL 수를 상수 개수로 묶어야 한다")
                .isLessThanOrEqualTo(4L);
    }
}
