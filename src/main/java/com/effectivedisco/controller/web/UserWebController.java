package com.effectivedisco.controller.web;

import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.dto.request.PasswordChangeRequest;
import com.effectivedisco.dto.request.ProfileEditRequest;
import com.effectivedisco.service.BlockService;
import com.effectivedisco.service.BookmarkService;
import com.effectivedisco.service.FollowService;
import com.effectivedisco.service.ImageService;
import com.effectivedisco.service.NotificationSettingService;
import com.effectivedisco.service.PostReadService;
import com.effectivedisco.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 사용자 프로필·설정 웹 컨트롤러.
 *
 * GET  /users/{username}   → 공개 프로필 페이지
 * GET  /settings           → 내 설정 페이지 (로그인 필요)
 * POST /settings/profile   → bio·이메일 변경
 * POST /settings/password  → 비밀번호 변경
 * POST /settings/withdraw  → 회원 탈퇴
 */
@Controller
@RequiredArgsConstructor
public class UserWebController {

    private final UserService                userService;
    private final PostReadService            postReadService;
    private final BookmarkService            bookmarkService;
    private final FollowService              followService;
    private final BlockService               blockService;
    private final ImageService               imageService;
    private final NotificationSettingService notificationSettingService;

    /* ── 공개 프로필 ──────────────────────────────────────────── */

    @GetMapping("/bookmarks")
    public String bookmarks(@AuthenticationPrincipal UserDetails userDetails,
                            @RequestParam(required = false) Long folderId,
                            Model model) {
        String username = userDetails.getUsername();
        // 폴더 목록 (사이드바 표시용)
        model.addAttribute("folders", bookmarkService.getFolders(username));
        model.addAttribute("selectedFolderId", folderId);

        // 폴더별 또는 전체 북마크 조회
        if (folderId != null) {
            model.addAttribute("bookmarks", bookmarkService.getBookmarksByFolder(username, folderId));
        } else {
            model.addAttribute("bookmarks", bookmarkService.getBookmarks(username));
        }
        return "users/bookmarks";
    }

    /** 북마크 폴더 생성 */
    @PostMapping("/bookmarks/folders")
    public String createBookmarkFolder(@AuthenticationPrincipal UserDetails userDetails,
                                        @RequestParam String name,
                                        RedirectAttributes redirectAttributes) {
        try {
            bookmarkService.createFolder(userDetails.getUsername(), name);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("folderError", e.getMessage());
        }
        return "redirect:/bookmarks";
    }

    /** 북마크 폴더 이름 변경 */
    @PostMapping("/bookmarks/folders/{folderId}/rename")
    public String renameBookmarkFolder(@AuthenticationPrincipal UserDetails userDetails,
                                        @PathVariable Long folderId,
                                        @RequestParam String name,
                                        RedirectAttributes redirectAttributes) {
        try {
            bookmarkService.renameFolder(userDetails.getUsername(), folderId, name);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("folderError", e.getMessage());
        }
        return "redirect:/bookmarks?folderId=" + folderId;
    }

    /** 북마크 폴더 삭제 (북마크는 미분류로 복원) */
    @PostMapping("/bookmarks/folders/{folderId}/delete")
    public String deleteBookmarkFolder(@AuthenticationPrincipal UserDetails userDetails,
                                        @PathVariable Long folderId) {
        bookmarkService.deleteFolder(userDetails.getUsername(), folderId);
        return "redirect:/bookmarks";
    }

    /** 북마크를 다른 폴더로 이동 */
    @PostMapping("/bookmarks/{postId}/move")
    public String moveBookmark(@AuthenticationPrincipal UserDetails userDetails,
                               @PathVariable Long postId,
                               @RequestParam(required = false) Long folderId) {
        bookmarkService.moveBookmarkToFolder(userDetails.getUsername(), postId, folderId);
        return "redirect:/bookmarks" + (folderId != null ? "?folderId=" + folderId : "");
    }

    @GetMapping("/users/{username}")
    public String profile(@PathVariable String username,
                          @RequestParam(defaultValue = "0") int page,
                          @AuthenticationPrincipal UserDetails userDetails,
                          Model model) {
        model.addAttribute("profile", userService.getProfile(username));
        model.addAttribute("posts", postReadService.getPostsByAuthor(username, page, 10));

        boolean isOther = userDetails != null && !userDetails.getUsername().equals(username);

        // 팔로우 여부 (팔로우 버튼 상태 결정)
        boolean isFollowing = isOther && followService.isFollowing(userDetails.getUsername(), username);
        model.addAttribute("isFollowing", isFollowing);

        // 차단 여부 (차단 버튼 상태 결정)
        boolean isBlocking = isOther && blockService.isBlocking(userDetails.getUsername(), username);
        model.addAttribute("isBlocking", isBlocking);

        return "users/profile";
    }

    /** 팔로우 등록 후 프로필 페이지로 리다이렉트한다. */
    @PostMapping("/users/{username}/follow")
    public String follow(@PathVariable String username,
                         @AuthenticationPrincipal UserDetails userDetails) {
        followService.follow(userDetails.getUsername(), username);
        return "redirect:/users/" + username;
    }

    /** 팔로우 해제 후 프로필 페이지로 리다이렉트한다. */
    @PostMapping("/users/{username}/unfollow")
    public String unfollow(@PathVariable String username,
                           @AuthenticationPrincipal UserDetails userDetails) {
        followService.unfollow(userDetails.getUsername(), username);
        return "redirect:/users/" + username;
    }

    /** 차단 등록 후 프로필 페이지로 리다이렉트한다. */
    @PostMapping("/users/{username}/block")
    public String block(@PathVariable String username,
                        @AuthenticationPrincipal UserDetails userDetails) {
        blockService.block(userDetails.getUsername(), username);
        return "redirect:/users/" + username;
    }

    /** 차단 해제 후 프로필 페이지로 리다이렉트한다. */
    @PostMapping("/users/{username}/unblock")
    public String unblock(@PathVariable String username,
                          @AuthenticationPrincipal UserDetails userDetails) {
        blockService.unblock(userDetails.getUsername(), username);
        return "redirect:/users/" + username;
    }

    /**
     * 차단 사용자 목록 페이지.
     * 현재 로그인 사용자가 차단한 사용자 목록을 최신순으로 표시한다.
     */
    @GetMapping("/blocks")
    public String blockedUsers(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        model.addAttribute("blocks", blockService.getBlockList(userDetails.getUsername()));
        return "users/blocks";
    }

    /**
     * 팔로워 목록 페이지.
     * 해당 사용자를 팔로우하는 사람들을 최신 팔로우 순으로 표시한다.
     */
    @GetMapping("/users/{username}/followers")
    public String followers(@PathVariable String username, Model model) {
        model.addAttribute("profile",   userService.getProfile(username));
        model.addAttribute("followers", followService.getFollowers(username));
        return "users/followers";
    }

    /**
     * 팔로잉 목록 페이지.
     * 해당 사용자가 팔로우하는 사람들을 최신 팔로우 순으로 표시한다.
     */
    @GetMapping("/users/{username}/following")
    public String following(@PathVariable String username, Model model) {
        model.addAttribute("profile",   userService.getProfile(username));
        model.addAttribute("followings", followService.getFollowings(username));
        return "users/following";
    }

    /**
     * 팔로우 피드 — 팔로우한 사용자들의 최신 게시물 목록.
     * 팔로잉이 없으면 빈 목록을 표시한다.
     */
    @GetMapping("/feed")
    public String feed(@AuthenticationPrincipal UserDetails userDetails,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("posts",
                followService.getFeed(userDetails.getUsername(), page, 20));
        // 차단된 사용자의 피드 게시물을 숨기기 위해 차단 목록을 전달
        model.addAttribute("blockedUsernames",
                blockService.getBlockedUsernames(userDetails.getUsername()));
        return "users/feed";
    }

    /* ── 설정 페이지 ──────────────────────────────────────────── */

    @GetMapping("/settings")
    public String settings(@AuthenticationPrincipal UserDetails userDetails,
                           Model model) {
        model.addAttribute("profile", userService.getProfile(userDetails.getUsername()));
        // 알림 수신 설정 (타입별 on/off 상태)
        model.addAttribute("notificationSettings",
                notificationSettingService.getSettings(userDetails.getUsername()));
        model.addAttribute("notificationTypes", NotificationType.values());
        return "users/settings";
    }

    /**
     * 알림 수신 설정 변경.
     * 각 알림 타입별 on/off 체크박스를 처리한다.
     */
    @PostMapping("/settings/notifications")
    public String updateNotificationSettings(@AuthenticationPrincipal UserDetails userDetails,
                                              @RequestParam(required = false) List<String> enabledTypes,
                                              RedirectAttributes redirectAttributes) {
        // 체크된 타입만 enabledTypes로 전달됨 — 모든 타입을 순회하며 on/off 설정
        Set<String> enabled = enabledTypes != null ? new HashSet<>(enabledTypes) : Collections.emptySet();
        for (NotificationType type : NotificationType.values()) {
            notificationSettingService.updateSetting(
                    userDetails.getUsername(), type, enabled.contains(type.name()));
        }
        redirectAttributes.addFlashAttribute("notificationMsg", "알림 설정이 저장되었습니다.");
        return "redirect:/settings";
    }

    /* ── 프로필 이미지 변경 ───────────────────────────────────── */

    /**
     * 프로필 이미지 업로드.
     * ImageService로 파일을 저장한 뒤 URL을 UserService에 전달해 User 엔티티에 반영한다.
     * 업로드 실패(형식·크기 오류) 시 플래시 에러를 띄우고 설정 페이지로 되돌아간다.
     */
    @PostMapping("/settings/profile-image")
    public String updateProfileImage(@AuthenticationPrincipal UserDetails userDetails,
                                     @RequestParam("image") MultipartFile image,
                                     RedirectAttributes redirectAttributes) {
        try {
            String url = imageService.store(image);
            if (url == null) {
                redirectAttributes.addFlashAttribute("profileError", "이미지 파일을 선택해 주세요.");
            } else {
                userService.updateProfileImage(userDetails.getUsername(), url);
                redirectAttributes.addFlashAttribute("profileMsg", "프로필 사진이 변경되었습니다.");
            }
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("profileError", e.getMessage());
        }
        return "redirect:/settings";
    }

    /* ── 프로필(bio·이메일) 변경 ─────────────────────────────── */

    @PostMapping("/settings/profile")
    public String updateProfile(@AuthenticationPrincipal UserDetails userDetails,
                                @ModelAttribute ProfileEditRequest req,
                                RedirectAttributes redirectAttributes) {
        try {
            userService.updateProfile(userDetails.getUsername(), req);
            // bio/email 변경 메시지는 bioMsg로 구분 (프로필 사진 메시지와 섹션 분리)
            redirectAttributes.addFlashAttribute("bioMsg", "프로필이 수정되었습니다.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("bioError", e.getMessage());
        }
        return "redirect:/settings";
    }

    /* ── 비밀번호 변경 ─────────────────────────────────────────── */

    @PostMapping("/settings/password")
    public String changePassword(@AuthenticationPrincipal UserDetails userDetails,
                                 @ModelAttribute PasswordChangeRequest req,
                                 RedirectAttributes redirectAttributes) {
        try {
            userService.changePassword(userDetails.getUsername(), req);
            redirectAttributes.addFlashAttribute("passwordMsg", "비밀번호가 변경되었습니다.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("passwordError", e.getMessage());
        }
        return "redirect:/settings";
    }

    /* ── 회원 탈퇴 ─────────────────────────────────────────────── */

    @PostMapping("/settings/withdraw")
    public String withdraw(@AuthenticationPrincipal UserDetails userDetails,
                           @RequestParam String password,
                           HttpServletRequest request,
                           RedirectAttributes redirectAttributes) {
        try {
            userService.withdraw(userDetails.getUsername(), password);
            // 세션 무효화 → 로그아웃 처리
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            return "redirect:/login?withdrawn";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("withdrawError", e.getMessage());
            return "redirect:/settings";
        }
    }
}
