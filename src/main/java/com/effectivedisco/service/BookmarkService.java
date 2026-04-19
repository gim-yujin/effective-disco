package com.effectivedisco.service;

import com.effectivedisco.domain.Bookmark;
import com.effectivedisco.domain.BookmarkFolder;
import com.effectivedisco.domain.Post;
import com.effectivedisco.domain.User;
import com.effectivedisco.dto.response.PostResponse;
import com.effectivedisco.repository.BookmarkFolderRepository;
import com.effectivedisco.repository.BookmarkRepository;
import com.effectivedisco.repository.PostRepository;
import com.effectivedisco.repository.RelationAtomicInserter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final BookmarkRepository       bookmarkRepository;
    private final BookmarkFolderRepository folderRepository;
    private final PostRepository           postRepository;
    private final UserLookupService        userLookupService;
    private final RelationAtomicInserter   relationAtomicInserter;

    /* ── 북마크 CRUD ─────────────────────────────────────────── */

    /**
     * 게시물을 북마크한다.
     * 이미 북마크된 상태이면 그대로 성공 처리한다.
     */
    @Transactional
    public void bookmark(String username, Long postId) {
        User user = userLookupService.findByUsername(username);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + postId));

        // 원자적 idempotent 삽입 (PG: ON CONFLICT DO NOTHING, H2: MERGE KEY). folder는 NULL로 시작.
        // user row에 FOR UPDATE를 걸던 기존 직렬화 경로를 제거해 동시 toggle 압력 하에서도
        // lock contention 없이 unique 제약만으로 중복을 방지한다.
        relationAtomicInserter.insertBookmark(user.getId(), post.getId(), LocalDateTime.now());
    }

    /**
     * 게시물 북마크를 해제한다.
     * 이미 해제된 상태이면 그대로 성공 처리한다.
     */
    @Transactional
    public void unbookmark(String username, Long postId) {
        User user = userLookupService.findByUsername(username);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + postId));

        // deleteByUserAndPost는 DELETE ... WHERE 단일 문으로 이미 원자적이다.
        // 반복 요청이어도 삭제 결과 0이 되어 최종 상태는 "북마크 해제"로 수렴한다.
        bookmarkRepository.deleteByUserAndPost(user, post);
    }

    /** 현재 사용자가 해당 게시물을 북마크했는지 여부 */
    public boolean isBookmarked(String username, Long postId) {
        User user = userLookupService.findByUsername(username);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + postId));
        return bookmarkRepository.existsByUserAndPost(user, post);
    }

    /** 사용자의 전체 북마크 목록을 최신순으로 반환 */
    public List<PostResponse> getBookmarks(String username) {
        User user = userLookupService.findByUsername(username);
        return bookmarkRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(b -> new PostResponse(b.getPost()))
                .toList();
    }

    /** 특정 폴더의 북마크 목록을 최신순으로 반환 */
    @Transactional(readOnly = true)
    public List<PostResponse> getBookmarksByFolder(String username, Long folderId) {
        User user = userLookupService.findByUsername(username);
        BookmarkFolder folder = findFolder(folderId, user);
        return bookmarkRepository.findByUserAndFolderOrderByCreatedAtDesc(user, folder).stream()
                .map(b -> new PostResponse(b.getPost()))
                .toList();
    }

    /** 미분류(폴더 없음) 북마크 목록을 최신순으로 반환 */
    @Transactional(readOnly = true)
    public List<PostResponse> getUncategorizedBookmarks(String username) {
        User user = userLookupService.findByUsername(username);
        return bookmarkRepository.findByUserAndFolderIsNullOrderByCreatedAtDesc(user).stream()
                .map(b -> new PostResponse(b.getPost()))
                .toList();
    }

    /**
     * 북마크를 특정 폴더로 이동한다.
     * folderId가 null이면 미분류로 이동한다.
     */
    @Transactional
    public void moveBookmarkToFolder(String username, Long postId, Long folderId) {
        User user = userLookupService.findByUsernameForUpdate(username);
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("게시물을 찾을 수 없습니다: " + postId));
        Bookmark bookmark = bookmarkRepository.findByUserAndPost(user, post)
                .orElseThrow(() -> new IllegalArgumentException("해당 북마크를 찾을 수 없습니다"));
        BookmarkFolder folder = folderId != null ? findFolder(folderId, user) : null;
        bookmark.moveToFolder(folder);
    }

    /* ── 폴더 CRUD ───────────────────────────────────────────── */

    /** 사용자의 북마크 폴더 목록 (이름순) */
    @Transactional(readOnly = true)
    public List<BookmarkFolder> getFolders(String username) {
        User user = userLookupService.findByUsername(username);
        return folderRepository.findByUserOrderByNameAsc(user);
    }

    /** 새 북마크 폴더를 생성한다 */
    @Transactional
    public BookmarkFolder createFolder(String username, String folderName) {
        User user = userLookupService.findByUsername(username);
        if (folderRepository.findByUserAndName(user, folderName).isPresent()) {
            throw new IllegalArgumentException("이미 존재하는 폴더 이름입니다: " + folderName);
        }
        return folderRepository.save(new BookmarkFolder(user, folderName));
    }

    /** 북마크 폴더 이름을 변경한다 */
    @Transactional
    public BookmarkFolder renameFolder(String username, Long folderId, String newName) {
        User user = userLookupService.findByUsername(username);
        BookmarkFolder folder = findFolder(folderId, user);
        // 같은 이름으로 변경하면 무시
        if (!folder.getName().equals(newName)) {
            if (folderRepository.findByUserAndName(user, newName).isPresent()) {
                throw new IllegalArgumentException("이미 존재하는 폴더 이름입니다: " + newName);
            }
            folder.rename(newName);
        }
        return folder;
    }

    /**
     * 북마크 폴더를 삭제한다.
     * 폴더에 속한 북마크는 미분류로 복원된다 (삭제되지 않음).
     */
    @Transactional
    public void deleteFolder(String username, Long folderId) {
        User user = userLookupService.findByUsername(username);
        BookmarkFolder folder = findFolder(folderId, user);
        // 폴더에 속한 북마크를 미분류로 복원
        bookmarkRepository.clearFolder(folder);
        folderRepository.delete(folder);
    }

    /* ── private helpers ─────────────────────────────────────── */

    private BookmarkFolder findFolder(Long folderId, User user) {
        return folderRepository.findByIdAndUser(folderId, user)
                .orElseThrow(() -> new IllegalArgumentException("폴더를 찾을 수 없습니다: " + folderId));
    }
}
