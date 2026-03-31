package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.Tag;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.dto.response.PostScrollCursor;
import com.effectivedisco.dto.response.PostScrollResponse;
import com.effectivedisco.loadtest.LoadTestStepProfiler;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 게시물 읽기 전용 서비스.
 *
 * PostService(쓰기)와 분리하여 단일 책임 원칙을 지킨다.
 * 목록 조회, 검색, 무한 스크롤, 태그/게시판 필터 등
 * 모든 읽기 경로를 이 클래스에서 담당한다.
 *
 * 모든 public 메서드는 @Transactional(readOnly = true)로 표시하여
 * Hibernate flush를 생략하고 DB 레플리카 라우팅이 가능하도록 한다.
 */
@Service
@RequiredArgsConstructor
public class PostReadService {

    private final PostRepository      postRepository;
    private final UserRepository      userRepository;
    private final PostLikeRepository  postLikeRepository;
    private final TagRepository       tagRepository;
    private final BoardRepository     boardRepository;
    private final LoadTestStepProfiler loadTestStepProfiler;

    /* ── 페이지 기반 목록 조회 ─────────────────────────────── */

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
        Board board = resolveBoard(boardSlug);

        // 정렬 기준 정규화 — 지원하지 않는 값은 최신순으로 폴백
        String sortKey = normalizeSortKey(sort);

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

    /* ── 무한 스크롤 (커서 기반) ───────────────────────────── */

    /**
     * 게시판 내 무한 스크롤 목록 조회.
     * seek cursor 방식으로 중복/누락 없이 페이지를 이동한다.
     * 차단된 사용자의 게시물은 결과에서 제외한다.
     */
    @Transactional(readOnly = true)
    public PostScrollResponse getBoardScrollPosts(int size,
                                                  String boardSlug,
                                                  String sort,
                                                  LocalDateTime cursorCreatedAt,
                                                  Long cursorId,
                                                  Set<String> blockedUsernames) {
        String sortKey = normalizeSortKey(sort);
        if (!"latest".equals(sortKey)) {
            throw new IllegalArgumentException("무한 스크롤은 최신순 게시판 목록만 지원합니다.");
        }
        if (boardSlug == null || boardSlug.isBlank()) {
            throw new IllegalArgumentException("무한 스크롤에는 boardSlug가 필요합니다.");
        }

        Board board = boardRepository.findBySlug(boardSlug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시판: " + boardSlug));

        // 문제 해결:
        // 무한 스크롤은 page 번호와 total count 대신 seek cursor 만 유지해야 중복/누락과 count(*) 압박을 줄일 수 있다.
        // 또한 size를 제한해 클라이언트가 과도한 batch를 요청하며 pool을 오래 점유하는 경로도 막는다.
        int pageSize = clampPageSize(size);
        Pageable limit = PageRequest.of(0, pageSize);
        Slice<PostRepository.PostListRow> slice = loadBoardScrollSlice(
                board,
                limit,
                cursorCreatedAt,
                cursorId,
                blockedUsernames == null ? Set.of() : blockedUsernames
        );

        List<PostResponse> content = toPostResponseProjectionList(slice.getContent());
        PostScrollCursor nextCursor = resolveNextScrollCursor(slice, sortKey);
        return new PostScrollResponse(content, slice.hasNext(), nextCursor.createdAt(), nextCursor.sortValue(), nextCursor.id());
    }

    /**
     * API용 무한 스크롤 조회.
     * 키워드·태그·정렬 조건을 조합하여 커서 기반 슬라이스를 반환한다.
     */
    @Transactional(readOnly = true)
    public PostScrollResponse getPostSlice(int size,
                                           String keyword,
                                           String tag,
                                           String boardSlug,
                                           String sort,
                                           LocalDateTime cursorCreatedAt,
                                           Long cursorSortValue,
                                           Long cursorId) {
        String sortKey = normalizeSortKey(sort);
        if ((cursorCreatedAt == null) != (cursorId == null)) {
            throw new IllegalArgumentException("cursorCreatedAt 과 cursorId 는 함께 전달해야 합니다.");
        }
        if (requiresRankedCursor(sortKey) && cursorId != null && cursorSortValue == null) {
            throw new IllegalArgumentException("좋아요순/댓글순 cursor 요청에는 cursorSortValue 가 필요합니다.");
        }
        if ((keyword != null && !keyword.isBlank()) || (tag != null && !tag.isBlank())) {
            if (!"latest".equals(sortKey)) {
                throw new IllegalArgumentException("키워드/태그 검색 slice 는 latest 정렬만 지원합니다.");
            }
        }

        Board board = resolveBoard(boardSlug);
        if (board == null
                && (keyword == null || keyword.isBlank())
                && (tag == null || tag.isBlank())) {
            throw new IllegalArgumentException("slice API 는 boardSlug 또는 keyword/tag 가 필요합니다.");
        }

        int pageSize = clampPageSize(size);
        Pageable limit = PageRequest.of(0, pageSize);
        Slice<PostRepository.PostListRow> slice = loadApiSlice(
                board,
                limit,
                keyword,
                tag,
                sortKey,
                cursorCreatedAt,
                cursorSortValue,
                cursorId
        );

        List<PostResponse> content = toPostResponseProjectionList(slice.getContent());
        PostScrollCursor nextCursor = resolveNextScrollCursor(slice, sortKey);
        return new PostScrollResponse(content, slice.hasNext(), nextCursor.createdAt(), nextCursor.sortValue(), nextCursor.id());
    }

    /* ── 단일 조회 / 작성자별 / 초안 ─────────────────────── */

    /** 단일 게시물 조회 */
    public PostResponse getPost(Long id) {
        return new PostResponse(findPost(id));
    }

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

    /* ── 태그 ─────────────────────────────────────────────── */

    /** 전체 태그 이름 목록 (태그 필터 바 렌더링용) */
    public List<String> getAllTagNames() {
        return tagRepository.findAllByOrderByNameAsc().stream()
                .map(Tag::getName)
                .collect(Collectors.toList());
    }

    /** 인기 태그 이름 목록 (사용 빈도 내림차순, 최대 limit개) */
    @Cacheable("popularTags")
    public List<String> getPopularTagNames(int limit) {
        return postRepository.findPopularTagNames(PageRequest.of(0, limit));
    }

    /* ── 고정 게시물 / 좋아요 여부 ────────────────────────── */

    /** 특정 게시판의 고정 공개 게시물 목록 (관리자 고정, 최신순, 초안 제외) */
    @Transactional(readOnly = true)
    public List<PostResponse> getPinnedPosts(String boardSlug) {
        if (boardSlug == null || boardSlug.isBlank()) return List.of();
        Board board = boardRepository.findBySlug(boardSlug).orElse(null);
        if (board == null) return List.of();
        return toPostResponseList(postRepository.findByBoardAndPinnedTrueAndDraftFalseOrderByCreatedAtDesc(board));
    }

    /** 현재 사용자가 특정 게시물에 좋아요를 눌렀는지 확인 */
    public boolean isLikedByUser(Long postId, String username) {
        Post post = findPost(postId);
        User user = findUser(username);
        return postLikeRepository.existsByPostAndUser(post, user);
    }

    /* ── DTO 변환 ─────────────────────────────────────────── */

    /**
     * Post 엔티티 페이지를 PostResponse 페이지로 변환한다.
     * 변환 전 tags/images를 preload하여 N+1을 방지한다.
     */
    private Page<PostResponse> toPostResponseEntityPage(Page<Post> posts) {
        preloadListRelations(posts.getContent());
        return posts.map(PostResponse::new);
    }

    /**
     * Post 엔티티 리스트를 PostResponse 리스트로 변환한다.
     * preloadListRelations로 N+1을 방지한 뒤 매핑한다.
     */
    private List<PostResponse> toPostResponseList(List<Post> posts) {
        preloadListRelations(posts);
        return posts.stream()
                .map(PostResponse::new)
                .toList();
    }

    /**
     * projection(PostListRow) 페이지를 PostResponse 페이지로 변환한다.
     * total count는 원본 Page에서 가져온다.
     */
    private Page<PostResponse> toPostResponseProjectionPage(Page<PostRepository.PostListRow> posts) {
        List<PostResponse> content = toPostResponseProjectionList(posts.getContent());
        return new PageImpl<>(content, posts.getPageable(), posts.getTotalElements());
    }

    /**
     * projection(PostListRow) 리스트를 PostResponse 리스트로 변환한다.
     *
     * 문제 해결:
     * 목록/검색은 projection 으로 본문 select 를 얇게 만들었지만, 태그/이미지 컬렉션은 여전히 필요하다.
     * collection join 을 본문 쿼리에 합치면 페이지네이션과 count 해석이 깨지므로 현재 페이지 ID만 기준으로
     * 태그/이미지 row 를 한 번씩 더 읽어 "얇은 본문 + 상수 개수 보조 쿼리" 구조로 유지한다.
     */
    private List<PostResponse> toPostResponseProjectionList(List<PostRepository.PostListRow> posts) {
        if (posts.isEmpty()) {
            return List.of();
        }

        List<Long> postIds = posts.stream()
                .map(PostRepository.PostListRow::getId)
                .toList();

        // 태그를 게시물 ID별로 그룹핑 — 보조 쿼리 1회
        Map<Long, List<String>> tagsByPostId = new LinkedHashMap<>();
        for (PostRepository.PostTagRow tagRow : postRepository.findTagRowsByPostIdIn(postIds)) {
            tagsByPostId.computeIfAbsent(tagRow.getPostId(), ignored -> new ArrayList<>())
                    .add(tagRow.getTagName());
        }

        // 이미지를 게시물 ID별로 그룹핑 — 보조 쿼리 1회
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

    /**
     * 게시물의 이미지 URL을 결정한다.
     * PostImage 테이블에 행이 있으면 그것을 사용하고,
     * 없으면 레거시 단일 이미지 URL을 폴백으로 사용한다.
     */
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

    /* ── 슬라이스 로딩 (무한 스크롤 내부) ─────────────────── */

    /**
     * 게시판 무한 스크롤 슬라이스를 로딩한다.
     * 차단 사용자 목록이 있으면 해당 작성자 게시물을 제외한다.
     */
    private Slice<PostRepository.PostListRow> loadBoardScrollSlice(Board board,
                                                                   Pageable limit,
                                                                   LocalDateTime cursorCreatedAt,
                                                                   Long cursorId,
                                                                   Set<String> blockedUsernames) {
        List<String> blocked = blockedUsernames.stream()
                .filter(username -> username != null && !username.isBlank())
                .sorted()
                .toList();

        if (cursorCreatedAt == null || cursorId == null) {
            return blocked.isEmpty()
                    ? postRepository.findScrollPostListRowsByBoardOrderByCreatedAtDesc(board, limit)
                    : postRepository.findScrollPostListRowsByBoardOrderByCreatedAtDescAndAuthorUsernameNotIn(
                            board, blocked, limit
                    );
        }

        return blocked.isEmpty()
                ? postRepository.findScrollPostListRowsByBoardAndCreatedAtBefore(board, cursorCreatedAt, cursorId, limit)
                : postRepository.findScrollPostListRowsByBoardAndCreatedAtBeforeAndAuthorUsernameNotIn(
                        board, cursorCreatedAt, cursorId, blocked, limit
                );
    }

    /**
     * API 슬라이스 로딩 분기.
     * 태그 > 키워드 > 게시판 browse 순으로 우선순위를 적용한다.
     */
    private Slice<PostRepository.PostListRow> loadApiSlice(Board board,
                                                           Pageable limit,
                                                           String keyword,
                                                           String tag,
                                                           String sortKey,
                                                           LocalDateTime cursorCreatedAt,
                                                           Long cursorSortValue,
                                                           Long cursorId) {
        if (tag != null && !tag.isBlank()) {
            return loadTagSlice(board, tag, limit, cursorCreatedAt, cursorId);
        }
        if (keyword != null && !keyword.isBlank()) {
            return loadSearchSlice(board, keyword, limit, cursorCreatedAt, cursorId);
        }
        if (board == null) {
            throw new IllegalArgumentException("게시판 browse slice 에는 boardSlug 가 필요합니다.");
        }
        return loadBrowseSlice(board, limit, sortKey, cursorCreatedAt, cursorSortValue, cursorId);
    }

    /**
     * 게시판 browse 슬라이스.
     * 정렬 기준(latest/likes/comments)에 따라 분기하며,
     * 부하 테스트 프로파일링 포인트를 함께 설정한다.
     */
    private Slice<PostRepository.PostListRow> loadBrowseSlice(Board board,
                                                              Pageable limit,
                                                              String sortKey,
                                                              LocalDateTime cursorCreatedAt,
                                                              Long cursorSortValue,
                                                              Long cursorId) {
        // 문제 해결:
        // broad mixed 최소 재현 조합에서는 browse feed 와 search 가 같이 돌 때만 pool timeout 이 커졌다.
        // browse 전용 slice 를 분리 profile 로 남기면 "feed rows" 가 먼저 비싸지는지,
        // search rows 가 먼저 비싸지는지를 같은 부하에서 분리해서 볼 수 있다.
        String[] profileNames = switch (sortKey) {
            case "likes" -> new String[]{"post.list.browse.rows", "post.list.search.sort.rows", "post.list.search.sort.likes.rows"};
            case "comments" -> new String[]{"post.list.browse.rows", "post.list.search.sort.rows", "post.list.search.sort.comments.rows"};
            default -> new String[]{"post.list.browse.rows"};
        };
        return profileSlicePath(
                () -> switch (sortKey) {
                    case "likes" -> loadRankedBrowseSlice(board, limit, cursorCreatedAt, cursorSortValue, cursorId, true);
                    case "comments" -> loadRankedBrowseSlice(board, limit, cursorCreatedAt, cursorSortValue, cursorId, false);
                    default -> loadLatestBrowseSlice(board, limit, cursorCreatedAt, cursorId);
                },
                profileNames
        );
    }

    /**
     * 최신순 browse — id-first 구조.
     * 먼저 정렬된 게시물 ID만 가져온 뒤, 해당 ID의 상세 행을 조회한다.
     */
    private Slice<PostRepository.PostListRow> loadLatestBrowseSlice(Board board,
                                                                    Pageable limit,
                                                                    LocalDateTime cursorCreatedAt,
                                                                    Long cursorId) {
        int batchSize = limit.isPaged() ? limit.getPageSize() : 20;
        Pageable idWindow = PageRequest.of(0, batchSize + 1);

        List<Long> postIds = (cursorCreatedAt == null || cursorId == null)
                ? postRepository.findScrollPostIdsByBoardOrderByCreatedAtDesc(board, idWindow)
                : postRepository.findScrollPostIdsByBoardAndCreatedAtBefore(board, cursorCreatedAt, cursorId, idWindow);

        boolean hasNext = postIds.size() > batchSize;
        if (hasNext) {
            postIds = postIds.subList(0, batchSize);
        }
        return toBoardScopedSlice(board, limit, postIds, hasNext);
    }

    /**
     * 좋아요/댓글순 browse — id-first 구조.
     * ranked browse 를 latest 와 같은 id-first → small row batch 구조로 맞춰
     * pool 점유 시간 비대칭을 방지한다.
     */
    private Slice<PostRepository.PostListRow> loadRankedBrowseSlice(Board board,
                                                                    Pageable limit,
                                                                    LocalDateTime cursorCreatedAt,
                                                                    Long cursorSortValue,
                                                                    Long cursorId,
                                                                    boolean likesSort) {
        int batchSize = limit.isPaged() ? limit.getPageSize() : 20;
        Pageable idWindow = PageRequest.of(0, batchSize + 1);

        List<Long> postIds;
        if (cursorCreatedAt == null || cursorId == null || cursorSortValue == null) {
            postIds = likesSort
                    ? postRepository.findScrollPostIdsByBoardOrderByLikeCountDesc(board, idWindow)
                    : postRepository.findScrollPostIdsByBoardOrderByCommentCountDesc(board, idWindow);
        } else {
            postIds = likesSort
                    ? postRepository.findScrollPostIdsByBoardAndLikeCountAfter(
                    board, cursorSortValue, cursorCreatedAt, cursorId, idWindow
            )
                    : postRepository.findScrollPostIdsByBoardAndCommentCountAfter(
                    board, cursorSortValue, cursorCreatedAt, cursorId, idWindow
            );
        }

        boolean hasNext = postIds.size() > batchSize;
        if (hasNext) {
            postIds = postIds.subList(0, batchSize);
        }

        return toBoardScopedSlice(board, limit, postIds, hasNext);
    }

    /**
     * 게시물 ID 리스트를 게시판 컨텍스트 포함 슬라이스로 변환한다.
     *
     * 문제 해결:
     * board-scoped browse 최신/좋아요/댓글 정렬은 모두 "작은 id 집합 + author projection"으로 동일하게 수렴해야 한다.
     * 이렇게 해야 latest만 빠르고 ranked browse가 다시 느려지는 비대칭을 만들지 않는다.
     */
    private Slice<PostRepository.PostListRow> toBoardScopedSlice(Board board,
                                                                 Pageable limit,
                                                                 List<Long> postIds,
                                                                 boolean hasNext) {
        if (postIds.isEmpty()) {
            return new org.springframework.data.domain.SliceImpl<>(List.of(), limit, false);
        }

        Map<Long, PostRepository.BoardScopedPostListRow> rowsById = postRepository.findBoardScopedPostListRowsByIdIn(postIds).stream()
                .collect(Collectors.toMap(PostRepository.BoardScopedPostListRow::getId, row -> row));

        List<PostRepository.PostListRow> orderedRows = postIds.stream()
                .map(rowsById::get)
                .filter(java.util.Objects::nonNull)
                .map(row -> withBoardContext(row, board))
                .toList();

        return new org.springframework.data.domain.SliceImpl<>(orderedRows, limit, hasNext);
    }

    /**
     * 키워드 검색 슬라이스.
     *
     * 문제 해결:
     * 검색 경로를 search.rows 하나로만 두면 keyword/tag/sort 중 어느 경로가 실제 hot path 인지 알 수 없다.
     * keyword/tag/sort 와 board/global 을 nested profile 로 나눠
     * 다음 최적화가 어느 분기를 겨냥해야 하는지 부작용 없이 좁힌다.
     */
    private Slice<PostRepository.PostListRow> loadSearchSlice(Board board,
                                                              String keyword,
                                                              Pageable limit,
                                                              LocalDateTime cursorCreatedAt,
                                                              Long cursorId) {
        LocalDateTime effectiveCursorCreatedAt = cursorCreatedAt != null
                ? cursorCreatedAt
                : LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999);
        Long effectiveCursorId = cursorId != null ? cursorId : Long.MAX_VALUE;
        return profileSlicePath(
                () -> board == null
                        ? postRepository.searchPublicPostListRowsSlice(
                        keyword, limit, effectiveCursorCreatedAt, effectiveCursorId
                )
                        : postRepository.searchPublicPostListRowsInBoardSlice(
                        board, keyword, limit, effectiveCursorCreatedAt, effectiveCursorId
                ),
                "post.list.search.rows",
                "post.list.search.keyword.rows",
                board == null ? "post.list.search.keyword.global.rows" : "post.list.search.keyword.board.rows"
        );
    }

    /**
     * 태그 필터 슬라이스.
     *
     * 문제 해결:
     * tag filter 도 browse/search 와 같은 목록 hot path 인데, 별도 profile 이 없으면
     * broad mixed 에서 keyword search 와 tag search 중 무엇이 먼저 느려지는지 구분할 수 없다.
     */
    private Slice<PostRepository.PostListRow> loadTagSlice(Board board,
                                                           String tag,
                                                           Pageable limit,
                                                           LocalDateTime cursorCreatedAt,
                                                           Long cursorId) {
        return profileSlicePath(
                () -> {
                    if (cursorCreatedAt == null || cursorId == null) {
                        return board == null
                                ? postRepository.findScrollPostListRowsByTagName(tag, limit)
                                : postRepository.findScrollPostListRowsByBoardAndTagName(board, tag, limit);
                    }
                    return board == null
                            ? postRepository.findScrollPostListRowsByTagNameAndCreatedAtBefore(tag, cursorCreatedAt, cursorId, limit)
                            : postRepository.findScrollPostListRowsByBoardAndTagNameAndCreatedAtBefore(board, tag, cursorCreatedAt, cursorId, limit);
                },
                "post.list.tag.rows",
                "post.list.search.tag.rows",
                board == null ? "post.list.search.tag.global.rows" : "post.list.search.tag.board.rows"
        );
    }

    /* ── 프로파일링 / 유틸리티 ────────────────────────────── */

    /**
     * 슬라이스 경로를 중첩 프로파일링 래퍼로 감싼다.
     * profileNames 배열의 바깥→안쪽 순서로 감싸 nested timing을 기록한다.
     */
    private Slice<PostRepository.PostListRow> profileSlicePath(LoadTestStepProfiler.ThrowingSupplier<Slice<PostRepository.PostListRow>> supplier,
                                                               String... profileNames) {
        LoadTestStepProfiler.ThrowingSupplier<Slice<PostRepository.PostListRow>> profiledSupplier = supplier;
        for (int i = profileNames.length - 1; i >= 0; i--) {
            String profileName = profileNames[i];
            LoadTestStepProfiler.ThrowingSupplier<Slice<PostRepository.PostListRow>> current = profiledSupplier;
            profiledSupplier = () -> loadTestStepProfiler.profileChecked(profileName, false, current);
        }
        try {
            return profiledSupplier.get();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Unexpected checked exception during search path profiling", throwable);
        }
    }

    /** 좋아요순/댓글순은 cursorSortValue가 필요한 정렬 기준인지 판별 */
    private boolean requiresRankedCursor(String sortKey) {
        return "likes".equals(sortKey) || "comments".equals(sortKey);
    }

    /** 슬라이스의 마지막 항목에서 다음 페이지 커서를 추출한다 */
    private PostScrollCursor resolveNextScrollCursor(Slice<PostRepository.PostListRow> slice, String sortKey) {
        if (slice.isEmpty()) {
            return new PostScrollCursor(null, null, null);
        }
        PostRepository.PostListRow last = slice.getContent().get(slice.getNumberOfElements() - 1);
        Long sortValue = switch (sortKey) {
            case "likes" -> last.getLikeCount();
            case "comments" -> (long) last.getCommentCount();
            default -> null;
        };
        return new PostScrollCursor(last.getCreatedAt(), sortValue, last.getId());
    }

    /** BoardScopedPostListRow에 게시판 이름/슬러그를 덧붙인 PostListRow 어댑터 생성 */
    private PostRepository.PostListRow withBoardContext(PostRepository.BoardScopedPostListRow row, Board board) {
        return new BoardScopedPostListRowView(row, board.getName(), board.getSlug());
    }

    /**
     * PostResponse 목록 변환 전 tags/images를 preload한다.
     *
     * 문제 해결:
     * PostResponse 목록 변환은 author/board/tags/images를 모두 접근한다.
     * 현재 페이지 ID만 모아 tags/images를 한 번씩 preload 하면
     * DTO 매핑 시점의 SQL 수를 "게시물 수 비례"가 아니라 "페이지당 상수"로 묶을 수 있다.
     */
    private void preloadListRelations(List<Post> posts) {
        if (posts.isEmpty()) {
            return;
        }

        List<Long> postIds = posts.stream()
                .map(Post::getId)
                .toList();

        postRepository.findAllWithTagsByIdIn(postIds);
        postRepository.findAllWithImagesByIdIn(postIds);
    }

    /* ── 공통 private 헬퍼 ────────────────────────────────── */

    /** 게시판 슬러그로 Board 조회. null/blank면 null 반환, 없으면 예외 */
    private Board resolveBoard(String boardSlug) {
        if (boardSlug == null || boardSlug.isBlank()) {
            return null;
        }
        return boardRepository.findBySlug(boardSlug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시판: " + boardSlug));
    }

    /** 정렬 기준 문자열을 소문자로 정규화. null이면 "latest" */
    private String normalizeSortKey(String sort) {
        return (sort != null) ? sort.trim().toLowerCase() : "latest";
    }

    /** 페이지 크기를 1~50 범위로 클램핑 */
    private int clampPageSize(int size) {
        return Math.max(1, Math.min(size, 50));
    }

    /** ID로 게시물 조회. 없으면 IllegalArgumentException */
    private Post findPost(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + id));
    }

    /** username으로 사용자 조회. 없으면 UsernameNotFoundException */
    private User findUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }

    /* ── BoardScopedPostListRow 어댑터 ────────────────────── */

    /**
     * BoardScopedPostListRow를 PostListRow 인터페이스에 맞추는 어댑터 레코드.
     * 게시판 이름/슬러그를 Board 엔티티에서 직접 주입한다.
     */
    private record BoardScopedPostListRowView(PostRepository.BoardScopedPostListRow delegate,
                                              String boardName,
                                              String boardSlug) implements PostRepository.PostListRow {

        @Override
        public Long getId() {
            return delegate.getId();
        }

        @Override
        public String getTitle() {
            return delegate.getTitle();
        }

        @Override
        public String getContent() {
            return delegate.getContent();
        }

        @Override
        public LocalDateTime getCreatedAt() {
            return delegate.getCreatedAt();
        }

        @Override
        public LocalDateTime getUpdatedAt() {
            return delegate.getUpdatedAt();
        }

        @Override
        public int getCommentCount() {
            return delegate.getCommentCount();
        }

        @Override
        public long getLikeCount() {
            return delegate.getLikeCount();
        }

        @Override
        public int getViewCount() {
            return delegate.getViewCount();
        }

        @Override
        public boolean isPinned() {
            return delegate.isPinned();
        }

        @Override
        public boolean isDraft() {
            return delegate.isDraft();
        }

        @Override
        public String getLegacyImageUrl() {
            return delegate.getLegacyImageUrl();
        }

        @Override
        public String getAuthorUsername() {
            return delegate.getAuthorUsername();
        }

        @Override
        public String getBoardName() {
            return boardName;
        }

        @Override
        public String getBoardSlug() {
            return boardSlug;
        }
    }
}
