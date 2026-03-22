package com.effectivedisco.service;

import com.effectivedisco.domain.Board;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.PostLike;
import com.effectivedisco.domain.Tag;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.request.PostRequest;
import com.effectivedisco.dto.response.LikeResponse;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BoardRepository;
import com.effectivedisco.repository.PostLikeRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

        Page<Post> posts;

        if (board != null && tag != null && !tag.isBlank()) {
            // 게시판 내 태그 필터 (태그 필터는 항상 최신순)
            posts = postRepository.findByBoardAndTagName(board, tag, pageable);
        } else if (board != null && keyword != null && !keyword.isBlank()) {
            // 게시판 내 키워드 검색 (검색은 항상 최신순)
            posts = postRepository.searchByKeywordInBoard(board, keyword, pageable);
        } else if (board != null) {
            // 게시판 전체 목록 — 정렬 기준 적용
            posts = switch (sortKey) {
                case "likes"    -> postRepository.findByBoardOrderByLikeCountDesc(board, pageable);
                case "comments" -> postRepository.findByBoardOrderByCommentCountDesc(board, pageable);
                default         -> postRepository.findByBoardOrderByCreatedAtDesc(board, pageable);
            };
        } else if (tag != null && !tag.isBlank()) {
            // 전체 게시판 태그 필터 (태그 필터는 항상 최신순)
            posts = postRepository.findByTagName(tag, pageable);
        } else if (keyword != null && !keyword.isBlank()) {
            // 전체 게시판 키워드 검색 (검색은 항상 최신순)
            posts = postRepository.searchByKeyword(keyword, pageable);
        } else {
            // 전체 목록 — 정렬 기준 적용
            posts = switch (sortKey) {
                case "likes"    -> postRepository.findAllOrderByLikeCountDesc(pageable);
                case "comments" -> postRepository.findAllOrderByCommentCountDesc(pageable);
                default         -> postRepository.findAllByOrderByCreatedAtDesc(pageable);
            };
        }

        return posts.map(post -> new PostResponse(post, postLikeRepository.countByPost(post)));
    }

    /**
     * 정렬 기준 없이 게시물 목록을 조회한다 (하위 호환용 — 최신순 기본값 적용).
     * REST API 컨트롤러 등 sort 파라미터를 명시하지 않는 기존 호출에서 사용한다.
     */
    public Page<PostResponse> getPosts(int page, int size,
                                       String keyword, String tag, String boardSlug) {
        return getPosts(page, size, keyword, tag, boardSlug, "latest");
    }

    /** 단일 게시물 조회 */
    public PostResponse getPost(Long id) {
        Post post = findPost(id);
        return new PostResponse(post, postLikeRepository.countByPost(post));
    }

    /**
     * 특정 사용자가 작성한 게시물을 최신순으로 페이징 반환.
     * 프로필 페이지의 "작성한 게시물" 섹션에 사용한다.
     */
    public Page<PostResponse> getPostsByAuthor(String username, int page, int size) {
        User user = findUser(username);
        return postRepository
                .findByAuthorOrderByCreatedAtDesc(user, PageRequest.of(page, size))
                .map(post -> new PostResponse(post, postLikeRepository.countByPost(post)));
    }

    /** 전체 태그 이름 목록 (태그 필터 바 렌더링용) */
    public List<String> getAllTagNames() {
        return tagRepository.findAllByOrderByNameAsc().stream()
                .map(Tag::getName)
                .collect(Collectors.toList());
    }

    /** 인기 태그 이름 목록 (사용 빈도 내림차순, 최대 15개) */
    public List<String> getPopularTagNames(int limit) {
        return postRepository.findPopularTagNames(PageRequest.of(0, limit));
    }

    /**
     * 게시물 생성.
     * request.boardSlug 가 있으면 해당 게시판에 속하도록 지정한다.
     */
    @Transactional
    public PostResponse createPost(PostRequest request, String username) {
        User user = findUser(username);

        // 게시판 지정 (없으면 null = 미분류)
        Board board = resolveBoard(request.getBoardSlug());

        Post post = Post.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .author(user)
                .board(board)
                .build();

        post.getTags().addAll(resolveTags(request.getTagsInput()));
        if (request.getImageUrl() != null) {
            post.setImageUrl(request.getImageUrl());
        }
        return new PostResponse(postRepository.save(post));
    }

    /**
     * 게시물 수정.
     * 작성자 본인만 수정 가능하다.
     * 태그는 기존 태그를 모두 지우고 새로 지정한다.
     */
    @Transactional
    public PostResponse updatePost(Long id, PostRequest request, String username) {
        Post post = findPost(id);
        checkOwnership(post.getAuthor().getUsername(), username);
        post.update(request.getTitle(), request.getContent());

        post.getTags().clear();
        post.getTags().addAll(resolveTags(request.getTagsInput()));

        if (request.getImageUrl() != null) {
            post.setImageUrl(request.getImageUrl());
        }

        return new PostResponse(post, postLikeRepository.countByPost(post));
    }

    /**
     * 게시물 삭제.
     * 작성자 본인만 삭제 가능하다.
     */
    @Transactional
    public void deletePost(Long id, String username) {
        Post post = findPost(id);
        checkOwnership(post.getAuthor().getUsername(), username);
        postRepository.delete(post);
    }

    /**
     * 관리자 전용 강제 삭제.
     */
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

    /** 특정 게시판의 고정 게시물 목록 (관리자 고정, 최신순) */
    public List<PostResponse> getPinnedPosts(String boardSlug) {
        if (boardSlug == null || boardSlug.isBlank()) return List.of();
        Board board = boardRepository.findBySlug(boardSlug).orElse(null);
        if (board == null) return List.of();
        return postRepository.findByBoardAndPinnedTrueOrderByCreatedAtDesc(board).stream()
                .map(p -> new PostResponse(p, postLikeRepository.countByPost(p)))
                .toList();
    }

    /**
     * 조회수 증가.
     * 트랜잭션 내에서 dirty checking으로 자동 저장된다.
     * 실제 중복 방지 로직은 웹 컨트롤러의 세션에서 처리한다.
     */
    @Transactional
    public void incrementViewCount(Long id) {
        findPost(id).incrementViewCount();
    }

    /**
     * 좋아요 토글.
     * 이미 좋아요 상태이면 취소, 아니면 추가한다.
     *
     * @return 토글 후의 좋아요 상태와 총 개수
     */
    @Transactional
    public LikeResponse toggleLike(Long postId, String username) {
        Post post = findPost(postId);
        User user = findUser(username);

        boolean wasLiked = postLikeRepository.existsByPostAndUser(post, user);
        if (wasLiked) {
            postLikeRepository.deleteByPostAndUser(post, user);
        } else {
            postLikeRepository.save(new PostLike(post, user));
            // 좋아요 추가 시에만 알림 생성 (취소 시에는 알림 없음)
            notificationService.notifyLike(post, username);
        }

        long count  = postLikeRepository.countByPost(post);
        boolean liked = postLikeRepository.existsByPostAndUser(post, user);
        return new LikeResponse(liked, count);
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
    private Set<Tag> resolveTags(String tagsInput) {
        if (tagsInput == null || tagsInput.isBlank()) return new HashSet<>();
        return Arrays.stream(tagsInput.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(name -> tagRepository.findByName(name)
                        .orElseGet(() -> tagRepository.save(new Tag(name))))
                .collect(Collectors.toSet());
    }

    /**
     * 슬러그로 게시판을 조회한다.
     * null 또는 빈 슬러그이면 null을 반환 (미분류 허용).
     */
    private Board resolveBoard(String boardSlug) {
        if (boardSlug == null || boardSlug.isBlank()) return null;
        return boardRepository.findBySlug(boardSlug)
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

    private void checkOwnership(String ownerUsername, String requestUsername) {
        if (!ownerUsername.equals(requestUsername)) {
            throw new AccessDeniedException("수정/삭제 권한이 없습니다");
        }
    }
}
