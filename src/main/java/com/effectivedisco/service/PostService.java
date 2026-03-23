package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostImage;
import com.effectivedisco.domain.PostLike;
import com.effectivedisco.domain.Tag;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.LikeResponse;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.loadtest.LoadTestStepProfiler;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository      postRepository;
    private final UserRepository      userRepository;
    private final PostLikeRepository  postLikeRepository;
    private final TagRepository       tagRepository;
    private final BoardRepository     boardRepository;
    private final NotificationService notificationService;
    private final LoadTestStepProfiler loadTestStepProfiler;
    private final EntityManager       entityManager;

    /**
     * 게시물 목록 조회.
     *
     * 우선순위: 게시판 필터 > 태그 필터 > 키워드 검색 > 전체 목록.
     * 게시판이 지정된 경우 해당 게시판 내에서만 키워드·태그 검색을 수행한다.
     *
     * @param boardSlug null이면 전체 게시판 검색
     * @param keyword   null이면 키워드 검색 미적용
     * @param tag       null이면 태그 필터 미적용
     * @param sort      정렬 기준: "latest"(최신순, 기본), "likes"(좋아요순), "comments"(댓글순)
     */
    @Transactional(readOnly = true)
    public Page<PostResponse> getPosts(int page, int size,
                                       String keyword, String tag,
                                       String boardSlug, String sort) {
        PageRequest pageable = PageRequest.of(page, size);

        // 게시판 슬러그가 있으면 해당 Board 엔티티를 로드
        Board board = null;
        if (boardSlug != null && !boardSlug.isBlank()) {
            board = boardRepository.findBySlug(boardSlug)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시판: " + boardSlug));
        }

        // 정렬 기준 정규화 — 지원하지 않는 값은 최신순으로 폴백
        String sortKey = (sort != null) ? sort.trim().toLowerCase() : "latest";

        Page<PostRepository.PostListRow> posts;

        if (board != null && tag != null && !tag.isBlank()) {
            // 게시판 내 태그 필터 (태그 필터는 항상 최신순, 초안 제외)
            posts = postRepository.findPublicPostListRowsByBoardAndTagName(board, tag, pageable);
        } else if (board != null && keyword != null && !keyword.isBlank()) {
            // 게시판 내 키워드 검색 (검색은 항상 최신순, 초안 제외)
            posts = postRepository.searchPublicPostListRowsInBoard(board, keyword, pageable);
        } else if (board != null) {
            // 게시판 전체 목록 — 정렬 기준 적용 (초안 제외)
            posts = switch (sortKey) {
                case "likes"    -> postRepository.findPostListRowsByBoardOrderByLikeCountDesc(board, pageable);
                case "comments" -> postRepository.findPostListRowsByBoardOrderByCommentCountDesc(board, pageable);
                default         -> postRepository.findPublicPostListRowsByBoardOrderByCreatedAtDesc(board, pageable);
            };
        } else if (tag != null && !tag.isBlank()) {
            // 전체 게시판 태그 필터 (태그 필터는 항상 최신순, 초안 제외)
            posts = postRepository.findPublicPostListRowsByTagName(tag, pageable);
        } else if (keyword != null && !keyword.isBlank()) {
            // 전체 게시판 키워드 검색 (검색은 항상 최신순, 초안 제외)
            posts = postRepository.searchPublicPostListRows(keyword, pageable);
        } else {
            // 전체 목록 — 정렬 기준 적용 (초안 제외)
            posts = switch (sortKey) {
                case "likes"    -> postRepository.findAllPostListRowsOrderByLikeCountDesc(pageable);
                case "comments" -> postRepository.findAllPostListRowsOrderByCommentCountDesc(pageable);
                default         -> postRepository.findPublicPostListRowsOrderByCreatedAtDesc(pageable);
            };
        }

        return toPostResponseProjectionPage(posts);
    }

    /**
     * 정렬 기준 없이 게시물 목록을 조회한다 (하위 호환용 — 최신순 기본값 적용).
     * REST API 컨트롤러 등 sort 파라미터를 명시하지 않는 기존 호출에서 사용한다.
     */
    @Transactional(readOnly = true)
    public Page<PostResponse> getPosts(int page, int size,
                                       String keyword, String tag, String boardSlug) {
        return getPosts(page, size, keyword, tag, boardSlug, "latest");
    }

    /** 단일 게시물 조회 */
    public PostResponse getPost(Long id) {
        return new PostResponse(findPost(id));
    }

    /**
     * 특정 사용자가 작성한 게시물을 최신순으로 페이징 반환.
     * 프로필 페이지의 "작성한 게시물" 섹션에 사용한다.
     */
    /**
     * 특정 사용자의 공개 게시물을 최신순으로 페이징 반환 (초안 제외).
     * 프로필 페이지의 "작성한 게시물" 섹션에 사용한다.
     */
    @Transactional(readOnly = true)
    public Page<PostResponse> getPostsByAuthor(String username, int page, int size) {
        User user = findUser(username);
        Page<Post> posts = postRepository.findByAuthorAndDraftFalseOrderByCreatedAtDesc(
                user, PageRequest.of(page, size)
        );
        return toPostResponseEntityPage(posts);
    }

    /**
     * 현재 사용자의 초안(미공개) 게시물을 최신순으로 페이징 반환.
     * 초안은 작성자 본인만 접근할 수 있다.
     *
     * @param username 현재 로그인 사용자명
     * @param page 페이지 번호 (0부터)
     * @param size 페이지 크기
     */
    @Transactional(readOnly = true)
    public Page<PostResponse> getDrafts(String username, int page, int size) {
        User user = findUser(username);
        Page<Post> posts = postRepository.findByAuthorAndDraftTrueOrderByCreatedAtDesc(
                user, PageRequest.of(page, size)
        );
        return toPostResponseEntityPage(posts);
    }

    /** 전체 태그 이름 목록 (태그 필터 바 렌더링용) */
    public List<String> getAllTagNames() {
        return tagRepository.findAllByOrderByNameAsc().stream()
                .map(Tag::getName)
                .collect(Collectors.toList());
    }

    /** 인기 태그 이름 목록 (사용 빈도 내림차순, 최대 15개) */
    @Cacheable("popularTags")
    public List<String> getPopularTagNames(int limit) {
        return postRepository.findPopularTagNames(PageRequest.of(0, limit));
    }

    /**
     * 게시물 생성.
     * request.boardSlug 가 있으면 해당 게시판에 속하도록 지정한다.
     */
    @CacheEvict(value = "popularTags", allEntries = true)
    @Transactional
    public PostResponse createPost(PostRequest request, String username) {
        UserRepository.PostCreateAuthorSnapshot authorSnapshot = loadTestStepProfiler.profile(
                "post.create.resolve-author",
                true,
                () -> findPostCreateAuthorSnapshot(username)
        );
        BoardRepository.BoardSummary boardSummary = loadTestStepProfiler.profile(
                "post.create.resolve-board",
                true,
                () -> resolveBoardSummary(request.getBoardSlug())
        );
        ResolvedTags resolvedTags = loadTestStepProfiler.profile(
                "post.create.resolve-tags",
                true,
                () -> resolveTagsForWrite(request.getTagsInput())
        );
        // 문제 해결:
        // createPost 는 작성자/게시판/태그를 각각 여러 번 조회하고, 저장 직후 응답 DTO에서 연관을 다시 따라가며
        // 불필요한 SELECT fan-out 을 만들고 있었다. projection + batch tag lookup + explicit response data 로
        // 쓰기 hot path 를 "입력 해석 / INSERT / join table 저장" 수준으로 고정해 pool 점유 시간을 줄인다.
        User authorReference = entityManager.getReference(User.class, authorSnapshot.getId());
        Board boardReference = boardSummary == null
                ? null
                : entityManager.getReference(Board.class, boardSummary.getId());
        List<String> imageUrls = List.copyOf(request.getImageUrls());

        Post post = Post.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .author(authorReference)
                .board(boardReference)
                .build();
        post.getTags().addAll(resolvedTags.entities());

        // 초안 여부 설정 — true이면 비공개 저장, false(기본)이면 즉시 공개
        if (request.isDraft()) {
            post.saveDraft();
        }

        // 다중 이미지 첨부 — PostImage 엔티티를 저장 전 컬렉션에 추가해
        // Post save 한 번으로 함께 flush 되도록 묶는다.
        for (int i = 0; i < imageUrls.size(); i++) {
            post.addImage(new PostImage(post, imageUrls.get(i), i));
        }
        Post saved = loadTestStepProfiler.profile("post.create.persist", true, () -> postRepository.save(post));
        return loadTestStepProfiler.profile(
                "post.create.response",
                true,
                () -> new PostResponse(
                        saved,
                        authorSnapshot.getUsername(),
                        resolvedTags.sortedNames(),
                        boardSummary != null ? boardSummary.getName() : null,
                        boardSummary != null ? boardSummary.getSlug() : null,
                        imageUrls
                )
        );
    }

    /**
     * 게시물 수정.
     * 작성자 본인만 수정 가능하다.
     * 태그는 기존 태그를 모두 지우고 새로 지정한다.
     */
    @CacheEvict(value = "popularTags", allEntries = true)
    @Transactional
    public PostResponse updatePost(Long id, PostRequest request, String username) {
        Post post = findPost(id);
        checkOwnership(post.getAuthor().getUsername(), username);
        post.update(request.getTitle(), request.getContent());

        post.getTags().clear();
        post.getTags().addAll(resolveTagsForWrite(request.getTagsInput()).entities());

        // 새 이미지가 업로드된 경우 기존 이미지를 모두 교체
        // post.clearImages() → orphanRemoval로 기존 PostImage 행 자동 삭제
        List<String> newImageUrls = request.getImageUrls();
        if (!newImageUrls.isEmpty()) {
            post.clearImages();
            for (int i = 0; i < newImageUrls.size(); i++) {
                post.addImage(new PostImage(post, newImageUrls.get(i), i));
            }
        }

        // 초안 여부 업데이트 — "등록/저장" 버튼이면 draft=false(공개), "초안으로 저장"이면 draft=true
        if (request.isDraft()) {
            post.saveDraft();
        } else {
            post.publish();
        }

        return new PostResponse(post);
    }

    /**
     * 게시물 삭제.
     * 작성자 본인만 삭제 가능하다.
     */
    @CacheEvict(value = "popularTags", allEntries = true)
    @Transactional
    public void deletePost(Long id, String username) {
        Post post = findPost(id);
        checkOwnership(post.getAuthor().getUsername(), username);
        postRepository.delete(post);
    }

    /**
     * 관리자 전용 강제 삭제.
     */
    @CacheEvict(value = "popularTags", allEntries = true)
    @Transactional
    public void adminDeletePost(Long id) {
        postRepository.delete(findPost(id));
    }

    /**
     * 관리자 전용 고정 핀 토글.
     * 이미 고정된 게시물이면 해제하고, 아니면 고정한다.
     */
    @Transactional
    public boolean adminPinToggle(Long id) {
        Post post = findPost(id);
        if (post.isPinned()) {
            post.unpin();
            return false;
        } else {
            post.pin();
            return true;
        }
    }

    /** 특정 게시판의 고정 공개 게시물 목록 (관리자 고정, 최신순, 초안 제외) */
    @Transactional(readOnly = true)
    public List<PostResponse> getPinnedPosts(String boardSlug) {
        if (boardSlug == null || boardSlug.isBlank()) return List.of();
        Board board = boardRepository.findBySlug(boardSlug).orElse(null);
        if (board == null) return List.of();
        return toPostResponseList(postRepository.findByBoardAndPinnedTrueAndDraftFalseOrderByCreatedAtDesc(board));
    }

    private Page<PostResponse> toPostResponseEntityPage(Page<Post> posts) {
        preloadListRelations(posts.getContent());
        return posts.map(PostResponse::new);
    }

    private List<PostResponse> toPostResponseList(List<Post> posts) {
        preloadListRelations(posts);
        return posts.stream()
                .map(PostResponse::new)
                .toList();
    }

    private Page<PostResponse> toPostResponseProjectionPage(Page<PostRepository.PostListRow> posts) {
        List<PostResponse> content = toPostResponseProjectionList(posts.getContent());
        return new PageImpl<>(content, posts.getPageable(), posts.getTotalElements());
    }

    private List<PostResponse> toPostResponseProjectionList(List<PostRepository.PostListRow> posts) {
        if (posts.isEmpty()) {
            return List.of();
        }

        List<Long> postIds = posts.stream()
                .map(PostRepository.PostListRow::getId)
                .toList();

        // 문제 해결:
        // 목록/검색은 projection 으로 본문 select 를 얇게 만들었지만, 태그/이미지 컬렉션은 여전히 필요하다.
        // collection join 을 본문 쿼리에 합치면 페이지네이션과 count 해석이 깨지므로 현재 페이지 ID만 기준으로
        // 태그/이미지 row 를 한 번씩 더 읽어 "얇은 본문 + 상수 개수 보조 쿼리" 구조로 유지한다.
        Map<Long, List<String>> tagsByPostId = new LinkedHashMap<>();
        for (PostRepository.PostTagRow tagRow : postRepository.findTagRowsByPostIdIn(postIds)) {
            tagsByPostId.computeIfAbsent(tagRow.getPostId(), ignored -> new ArrayList<>())
                    .add(tagRow.getTagName());
        }

        Map<Long, List<String>> imagesByPostId = new LinkedHashMap<>();
        for (PostRepository.PostImageRow imageRow : postRepository.findImageRowsByPostIdIn(postIds)) {
            imagesByPostId.computeIfAbsent(imageRow.getPostId(), ignored -> new ArrayList<>())
                    .add(imageRow.getImageUrl());
        }

        return posts.stream()
                .map(post -> new PostResponse(
                        post.getId(),
                        post.getTitle(),
                        post.getContent(),
                        post.getAuthorUsername(),
                        post.getCreatedAt(),
                        post.getUpdatedAt(),
                        post.getCommentCount(),
                        post.getLikeCount(),
                        post.getViewCount(),
                        tagsByPostId.getOrDefault(post.getId(), List.of()),
                        post.getBoardName(),
                        post.getBoardSlug(),
                        post.isPinned(),
                        post.isDraft(),
                        resolveImageUrls(post, imagesByPostId)
                ))
                .toList();
    }

    private List<String> resolveImageUrls(PostRepository.PostListRow post,
                                          Map<Long, List<String>> imagesByPostId) {
        List<String> imageUrls = imagesByPostId.get(post.getId());
        if (imageUrls != null && !imageUrls.isEmpty()) {
            return imageUrls;
        }
        if (post.getLegacyImageUrl() != null) {
            return List.of(post.getLegacyImageUrl());
        }
        return List.of();
    }

    private void preloadListRelations(List<Post> posts) {
        if (posts.isEmpty()) {
            return;
        }

        List<Long> postIds = posts.stream()
                .map(Post::getId)
                .toList();

        // 문제 해결:
        // PostResponse 목록 변환은 author/board/tags/images를 모두 접근한다.
        // 페이지 본문 쿼리만 실행하면 각 게시물마다 LAZY 컬렉션 select가 추가로 발생해
        // post.list 에서 N+1이 터진다. 현재 페이지 ID만 모아 tags/images를 한 번씩 preload 하면
        // DTO 매핑 시점의 SQL 수를 "게시물 수 비례"가 아니라 "페이지당 상수"로 묶을 수 있다.
        postRepository.findAllWithTagsByIdIn(postIds);
        postRepository.findAllWithImagesByIdIn(postIds);
    }

    /**
     * 초안을 발행(공개)한다.
     * 작성자 본인만 발행할 수 있다.
     * 이미 공개된 게시물에 호출하면 예외 없이 무시한다(멱등).
     *
     * @param id       발행할 게시물 ID
     * @param username 현재 로그인 사용자명
     * @throws AccessDeniedException 작성자가 아닌 경우
     */
    @Transactional
    public void publishDraft(Long id, String username) {
        Post post = findPost(id);
        checkOwnership(post.getAuthor().getUsername(), username);
        post.publish(); // 이미 공개 상태여도 무해함 (idempotent)
    }

    /**
     * 조회수 증가.
     * 트랜잭션 내에서 dirty checking으로 자동 저장된다.
     * 실제 중복 방지 로직은 웹 컨트롤러의 세션에서 처리한다.
     */
    @Transactional
    public void incrementViewCount(Long id) {
        postRepository.incrementViewCount(id);
    }

    /**
     * 게시물에 좋아요를 건다.
     * 이미 좋아요 상태이면 그대로 성공 처리한다.
     */
    @Transactional
    public LikeResponse likePost(Long postId, String username) {
        Post post = findPost(postId);
        User user = findUserForUpdate(username);

        // 문제 해결:
        // 토글 방식은 같은 사용자의 중복 요청이 동시에 들어오면
        // exists() 둘 다 false -> insert 두 번 시도로 이어질 수 있다.
        // 요청 주체 User 행을 먼저 잠그고 "좋아요 상태 보장" 연산으로 바꾸면
        // 같은 요청이 여러 번 와도 상태가 뒤집히지 않고 한 번만 반영된다.
        if (!postLikeRepository.existsByPostAndUser(post, user)) {
            postLikeRepository.save(new PostLike(post, user));
            postRepository.incrementLikeCount(postId);
            notificationService.notifyLike(post, username);
        }
        return buildLikeResponse(postId, true);
    }

    /**
     * 게시물 좋아요를 해제한다.
     * 이미 해제된 상태이면 그대로 성공 처리한다.
     */
    @Transactional
    public LikeResponse unlikePost(Long postId, String username) {
        Post post = findPost(postId);
        User user = findUserForUpdate(username);

        // 문제 해결:
        // delete 결과 행 수로 실제 상태 변경 여부를 판단하면
        // 동일한 "좋아요 해제" 요청이 여러 번 와도 likeCount를 한 번만 줄일 수 있다.
        long deleted = postLikeRepository.deleteByPostAndUser(post, user);
        if (deleted > 0) {
            postRepository.decrementLikeCount(postId);
        }
        return buildLikeResponse(postId, false);
    }

    /** 현재 사용자가 특정 게시물에 좋아요를 눌렀는지 확인 */
    public boolean isLikedByUser(Long postId, String username) {
        Post post = findPost(postId);
        User user = findUser(username);
        return postLikeRepository.existsByPostAndUser(post, user);
    }

    /* ── private helpers ──────────────────────────────────────── */

    /**
     * 태그 이름 문자열(콤마 구분)을 Tag 엔티티 Set으로 변환한다.
     * DB에 없는 태그는 자동으로 생성한다.
     */
    private ResolvedTags resolveTagsForWrite(String tagsInput) {
        Set<String> tagNames = parseTagNames(tagsInput);
        if (tagNames.isEmpty()) {
            return new ResolvedTags(new HashSet<>(), List.of());
        }

        List<Tag> existingTags = tagRepository.findAllByNameIn(tagNames);
        Map<String, Tag> existingByName = existingTags.stream()
                .collect(Collectors.toMap(Tag::getName, tag -> tag));

        List<Tag> missingTags = tagNames.stream()
                .filter(name -> !existingByName.containsKey(name))
                .map(Tag::new)
                .toList();
        List<Tag> savedMissingTags = missingTags.isEmpty() ? List.of() : tagRepository.saveAll(missingTags);

        Set<Tag> resolvedTags = new LinkedHashSet<>(existingTags);
        resolvedTags.addAll(savedMissingTags);

        return new ResolvedTags(
                resolvedTags,
                tagNames.stream().sorted().toList()
        );
    }

    /**
     * 문제 해결:
     * createPost/updatePost 는 태그 수만큼 findByName() 를 반복하면서 SQL fan-out 이 커졌다.
     * 요청 태그 이름을 먼저 정규화하고 한 번의 IN 조회로 existing tag 를 가져오면
     * 태그 해석 비용을 "태그 수 비례"가 아니라 "요청당 상수 + missing tag insert" 수준으로 줄일 수 있다.
     */
    private Set<String> parseTagNames(String tagsInput) {
        if (tagsInput == null || tagsInput.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(tagsInput.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private BoardRepository.BoardSummary resolveBoardSummary(String boardSlug) {
        if (boardSlug == null || boardSlug.isBlank()) {
            return null;
        }

        return boardRepository.findSummaryBySlug(boardSlug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시판: " + boardSlug));
    }

    private Post findPost(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + id));
    }

    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }

    private UserRepository.PostCreateAuthorSnapshot findPostCreateAuthorSnapshot(String username) {
        return userRepository.findPostCreateAuthorSnapshotByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }

    private User findUserForUpdate(String username) {
        return userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }

    private LikeResponse buildLikeResponse(Long postId, boolean liked) {
        return new LikeResponse(liked, postRepository.findLikeCountById(postId));
    }

    private void checkOwnership(String ownerUsername, String requestUsername) {
        if (!ownerUsername.equals(requestUsername)) {
            throw new AccessDeniedException("수정/삭제 권한이 없습니다");
        }
    }

    private record ResolvedTags(Set<Tag> entities, List<String> sortedNames) {}
}
