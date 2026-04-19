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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 게시물 쓰기 전용 서비스.
 *
 * 생성·수정·삭제·좋아요·고정 등 상태를 변경하는 명령만 담당한다.
 * 읽기 전용 조회 로직은 {@link PostReadService}로 분리했다.
 */
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository      postRepository;
    private final UserRepository      userRepository;
    private final PostLikeRepository  postLikeRepository;
    private final TagRepository       tagRepository;
    private final TagWriteService     tagWriteService;
    private final BoardRepository     boardRepository;
    private final NotificationService notificationService;
    private final LoadTestStepProfiler loadTestStepProfiler;
    private final EntityManager       entityManager;
    private final UserLookupService   userLookupService;

    /* ── 게시물 CRUD ──────────────────────────────────────── */

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

    /* ── 고정 / 발행 / 조회수 ─────────────────────────────── */

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

    /* ── 좋아요 ───────────────────────────────────────────── */

    /**
     * 게시물에 좋아요를 건다.
     * 이미 좋아요 상태이면 그대로 성공 처리한다.
     */
    @Transactional
    public LikeResponse likePost(Long postId, String username) {
        // 문제 해결:
        // like-focused 재현 조합에서는 post.list 와 좋아요 등록이 동시에 돌 때 어느 쪽이
        // pool과 SQL 시간을 먼저 밀어 올리는지 봐야 한다. service 내부에서 직접 profile을 남기면
        // AOP 누락 없이 좋아요 등록 경로의 wall/sql/statement count를 안정적으로 수집할 수 있다.
        return loadTestStepProfiler.profile(
                "post.like.add",
                true,
                () -> {
                    Post post = loadTestStepProfiler.profile(
                            "post.like.add.resolve-post", true, () -> findPost(postId));
                    User user = loadTestStepProfiler.profile(
                            "post.like.add.resolve-user", true, () -> findUserForUpdate(username));

                    boolean alreadyLiked = loadTestStepProfiler.profile(
                            "post.like.add.exists-check", true,
                            () -> postLikeRepository.existsByPostAndUser(post, user));
                    if (!alreadyLiked) {
                        loadTestStepProfiler.profile(
                                "post.like.add.insert", true,
                                () -> postLikeRepository.save(new PostLike(post, user)));
                        loadTestStepProfiler.profile(
                                "post.like.add.counter-increment", true,
                                () -> postRepository.incrementLikeCount(postId));
                        loadTestStepProfiler.profile(
                                "post.like.add.notify", true,
                                () -> notificationService.notifyLike(post, username));
                    }
                    return loadTestStepProfiler.profile(
                            "post.like.add.response", true,
                            () -> buildLikeResponse(postId, true));
                }
        );
    }

    /**
     * 게시물 좋아요를 해제한다.
     * 이미 해제된 상태이면 그대로 성공 처리한다.
     */
    @Transactional
    public LikeResponse unlikePost(Long postId, String username) {
        // 문제 해결:
        // 좋아요 해제도 등록과 다른 SQL/락 경로를 타므로 별도 profile로 남겨야
        // add/remove 중 어느 경로가 재현 조합에서 더 비싼지 비교할 수 있다.
        return loadTestStepProfiler.profile(
                "post.like.remove",
                true,
                () -> {
                    Post post = loadTestStepProfiler.profile(
                            "post.like.remove.resolve-post", true, () -> findPost(postId));
                    User user = loadTestStepProfiler.profile(
                            "post.like.remove.resolve-user", true, () -> findUserForUpdate(username));

                    long deleted = loadTestStepProfiler.profile(
                            "post.like.remove.delete", true,
                            () -> postLikeRepository.deleteByPostAndUser(post, user));
                    if (deleted > 0) {
                        loadTestStepProfiler.profile(
                                "post.like.remove.counter-decrement", true,
                                () -> postRepository.decrementLikeCount(postId));
                    }
                    return loadTestStepProfiler.profile(
                            "post.like.remove.response", true,
                            () -> buildLikeResponse(postId, false));
                }
        );
    }

    /* ── private helpers ──────────────────────────────────── */

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

        Set<String> missingTagNames = tagNames.stream()
                .filter(name -> !existingByName.containsKey(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missingTagNames.isEmpty()) {
            // 문제 해결:
            // broad mixed 에서 여러 createPost 가 같은 새 태그를 동시에 만들면
            // `find existing -> saveAll(missing)` 사이에서 unique(tags.name) race 가 난다.
            // 태그 생성만 별도 짧은 트랜잭션으로 분리하고 duplicate 는 흡수한 뒤
            // 최종 태그 집합을 다시 조회하면 게시물 작성은 멱등하게 계속 진행할 수 있다.
            tagWriteService.ensureTagsExist(missingTagNames);
        }

        List<Tag> resolvedTagList = tagRepository.findAllByNameIn(tagNames);

        Set<Tag> resolvedTags = new LinkedHashSet<>(resolvedTagList);

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

    /** boardSlug로 BoardSummary projection 조회. null/blank면 null 반환 */
    private BoardRepository.BoardSummary resolveBoardSummary(String boardSlug) {
        if (boardSlug == null || boardSlug.isBlank()) {
            return null;
        }

        return boardRepository.findSummaryBySlug(boardSlug)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 게시판: " + boardSlug));
    }

    /** ID로 게시물 조회. 없으면 IllegalArgumentException */
    private Post findPost(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + id));
    }

    /** username으로 사용자의 게시물 작성용 snapshot 조회 */
    private UserRepository.PostCreateAuthorSnapshot findPostCreateAuthorSnapshot(String username) {
        return userRepository.findPostCreateAuthorSnapshotByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));
    }

    /** username으로 사용자 조회 + 행 잠금 (좋아요 동시성 제어용) */
    private User findUserForUpdate(String username) {
        return userLookupService.findByUsernameForUpdate(username);
    }

    /** 좋아요 응답 DTO 생성 — DB에서 최신 좋아요 수를 조회 */
    private LikeResponse buildLikeResponse(Long postId, boolean liked) {
        return new LikeResponse(liked, postRepository.findLikeCountById(postId));
    }

    /** 게시물 소유자 확인. 소유자가 아니면 AccessDeniedException */
    private void checkOwnership(String ownerUsername, String requestUsername) {
        OwnershipChecker.check(ownerUsername, requestUsername);
    }

    /** 태그 해석 결과를 담는 값 객체. 엔티티 Set과 정렬된 이름 리스트를 함께 보관한다. */
    private record ResolvedTags(Set<Tag> entities, List<String> sortedNames) {}
}
