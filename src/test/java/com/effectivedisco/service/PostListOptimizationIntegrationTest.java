package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostImage;
import com.effectivedisco.domain.Tag;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostScrollResponse;
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
            assertThat(post.getContent())
                    .as("문제 해결 검증: post.list 는 목록에서 쓰지 않는 본문 전체를 다시 읽어 오지 않고 빈 summary content 로 유지해야 한다")
                    .isEmpty();
            assertThat(post.getTags()).containsExactly("java-" + tagPrefix, "spring-" + tagPrefix);
            assertThat(post.getImageUrls()).hasSize(2);
        });
        assertThat(statistics.getPrepareStatementCount())
                .as("문제 해결 검증: post.list 는 게시물 수만큼 LAZY select 를 늘리지 말고 현재 페이지 연관 데이터를 상수 개수 SQL 로 preload 해야 한다")
                .isLessThanOrEqualTo(4L);
    }

    @Test
    void getPosts_keywordSearchUsesBoundedStatementsAndStableCountQuery() {
        String tagPrefix = "search-opt";
        Board board = boardRepository.save(Board.builder()
                .name("개발게시판")
                .slug("dev-" + tagPrefix)
                .description("검색 최적화 게시판")
                .build());

        Tag spring = tagRepository.save(new Tag("spring-" + tagPrefix));
        Tag load = tagRepository.save(new Tag("load-" + tagPrefix));

        for (int i = 0; i < 8; i++) {
            User author = userRepository.save(User.builder()
                    .username("search-author" + i)
                    .email("search-author" + i + "@example.com")
                    .password("encoded-password")
                    .build());

            Post post = Post.builder()
                    .title(i < 5 ? "load-test-post-" + i : "other-post-" + i)
                    .content(i < 5 ? "content with load keyword " + i : "plain content " + i)
                    .author(author)
                    .board(board)
                    .build();
            post.getTags().add(spring);
            post.getTags().add(load);
            post.addImage(new PostImage(post, "/search-images/" + i + ".jpg", 0));
            postRepository.save(post);
        }

        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        Page<PostResponse> result = postService.getPosts(0, 10, "load", null, "dev-" + tagPrefix, "latest");

        assertThat(result.getContent()).hasSize(5);
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getContent()).allSatisfy(post -> {
            assertThat(post.getBoardSlug()).isEqualTo("dev-" + tagPrefix);
            assertThat(post.getAuthor()).startsWith("search-author");
            assertThat(post.getContent()).isEmpty();
            assertThat(post.getTags()).containsExactly("load-" + tagPrefix, "spring-" + tagPrefix);
            assertThat(post.getImageUrls()).hasSize(1);
        });
        assertThat(statistics.getPrepareStatementCount())
                .as("문제 해결 검증: board+keyword search 는 board lookup + page query + count query + tag/image batch 로 고정되어야 한다")
                .isLessThanOrEqualTo(5L);
    }

    @Test
    void getPostSlice_keywordSearchAvoidsCountQuery() {
        String tagPrefix = "slice-search";
        Board board = boardRepository.save(Board.builder()
                .name("Slice 검색 게시판")
                .slug("slice-" + tagPrefix)
                .description("slice 검색 최적화 게시판")
                .build());

        Tag spring = tagRepository.save(new Tag("spring-" + tagPrefix));

        for (int i = 0; i < 6; i++) {
            User author = userRepository.save(User.builder()
                    .username("slice-author" + i)
                    .email("slice-author" + i + "@example.com")
                    .password("encoded-password")
                    .build());

            Post post = Post.builder()
                    .title("load-slice-post-" + i)
                    .content("content with load keyword " + i)
                    .author(author)
                    .board(board)
                    .build();
            post.getTags().add(spring);
            post.addImage(new PostImage(post, "/slice-images/" + i + ".jpg", 0));
            postRepository.save(post);
        }

        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        PostScrollResponse result = postService.getPostSlice(5, "load", null, "slice-" + tagPrefix, "latest", null, null, null);

        assertThat(result.getContent()).hasSize(5);
        assertThat(result.isHasNext()).isTrue();
        assertThat(result.getNextCursorCreatedAt()).isNotNull();
        assertThat(result.getNextCursorId()).isNotNull();
        assertThat(statistics.getPrepareStatementCount())
                .as("문제 해결 검증: API search slice 는 count(*) 없이 board lookup + rows query + tag/image batch 수준으로 끝나야 한다")
                .isLessThanOrEqualTo(4L);
    }

    @Test
    void getPosts_keywordSearchStillMatchesAuthorUsername() {
        String tagPrefix = "author-search";
        Board board = boardRepository.save(Board.builder()
                .name("작성자검색게시판")
                .slug("author-" + tagPrefix)
                .description("작성자 검색 회귀 테스트")
                .build());

        User author = userRepository.save(User.builder()
                .username("fts-search-author")
                .email("fts-search-author@example.com")
                .password("encoded-password")
                .build());

        Post post = Post.builder()
                .title("title without keyword")
                .content("content without keyword")
                .author(author)
                .board(board)
                .build();
        postRepository.save(post);

        entityManager.flush();
        entityManager.clear();

        Page<PostResponse> result = postService.getPosts(0, 10, "search-author", null, null, "latest");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getAuthor()).isEqualTo("fts-search-author");
        assertThat(result.getTotalElements())
                .as("문제 해결 검증: FTS 도입 후에도 작성자 username 검색 의미는 substring 기반으로 유지되어야 한다")
                .isEqualTo(1);
    }

    @Test
    void getPosts_keywordSearchDeduplicatesAcrossFtsAndUsernameBranches() {
        String tagPrefix = "dedup-search";
        Board board = boardRepository.save(Board.builder()
                .name("중복검색게시판")
                .slug("dedup-" + tagPrefix)
                .description("검색 dedup 테스트")
                .build());

        User author = userRepository.save(User.builder()
                .username("load-author")
                .email("load-author@example.com")
                .password("encoded-password")
                .build());

        Post post = Post.builder()
                .title("load title")
                .content("load content")
                .author(author)
                .board(board)
                .build();
        post.addImage(new PostImage(post, "/dedup-images/1.jpg", 0));
        postRepository.save(post);

        entityManager.flush();
        entityManager.clear();
        statistics.clear();

        Page<PostResponse> result = postService.getPosts(0, 10, "load", null, "dedup-" + tagPrefix, "latest");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements())
                .as("문제 해결 검증: 같은 게시물이 FTS branch 와 username branch 를 동시에 만족해도 UNION dedup 으로 1건만 반환돼야 한다")
                .isEqualTo(1);
        assertThat(statistics.getPrepareStatementCount())
                .as("문제 해결 검증: branch 분리 후에도 board lookup + page query + count query + tag/image batch 범위를 유지해야 한다")
                .isLessThanOrEqualTo(5L);
    }
}
