package com.effectivedisco.service;

import com.effectivedisco.domain.Comment;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.CommentResponse;
import com.effectivedisco.repository.CommentRepository;
import com.effectivedisco.repository.PostRepository;
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
class CommentPageOptimizationIntegrationTest {

    @Autowired CommentService commentService;
    @Autowired UserRepository userRepository;
    @Autowired PostRepository postRepository;
    @Autowired CommentRepository commentRepository;
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
    void getCommentsPage_usesBoundedStatementsPerPage() {
        User author = userRepository.save(User.builder()
                .username("detail-author")
                .email("detail-author@example.com")
                .password("encoded")
                .build());
        Post post = postRepository.save(Post.builder()
                .title("댓글 많은 글")
                .content("본문")
                .author(author)
                .build());

        for (int i = 0; i < 6; i++) {
            User commenter = userRepository.save(User.builder()
                    .username("commenter-" + i)
                    .email("commenter-" + i + "@example.com")
                    .password("encoded")
                    .build());
            Comment parent = commentRepository.save(Comment.builder()
                    .content("parent-" + i)
                    .post(post)
                    .author(commenter)
                    .build());

            for (int j = 0; j < 2; j++) {
                User replier = userRepository.save(User.builder()
                        .username("reply-" + i + "-" + j)
                        .email("reply-" + i + "-" + j + "@example.com")
                        .password("encoded")
                        .build());
                commentRepository.save(Comment.builder()
                        .content("reply-" + i + "-" + j)
                        .post(post)
                        .author(replier)
                        .parent(parent)
                        .build());
            }
        }

        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        Page<CommentResponse> result = commentService.getCommentsPage(post.getId(), 0, 3);

        assertThat(result.getContent()).hasSize(3);
        assertThat(result.getTotalElements()).isEqualTo(6);
        assertThat(result.getContent()).allSatisfy(comment -> {
            assertThat(comment.getAuthor()).startsWith("commenter-");
            assertThat(comment.getReplies()).hasSize(2);
        });
        assertThat(statistics.getPrepareStatementCount())
                .as("문제 해결 검증: 댓글 상세는 top-level page + count + replies batch 수준으로 고정되어야 한다")
                .isLessThanOrEqualTo(3L);
    }
}
