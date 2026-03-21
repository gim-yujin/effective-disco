package com.effectivedisco.controller.web;

import com.effectivedisco.service.PostService;
import com.effectivedisco.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 사용자 프로필 웹 컨트롤러.
 *
 * GET /users/{username}  → 사용자 프로필 페이지
 * 인증 없이도 접근 가능한 공개 페이지다.
 */
@Controller
@RequiredArgsConstructor
public class UserWebController {

    private final UserService userService;
    private final PostService postService;

    /**
     * 사용자 프로필 페이지.
     *
     * 표시 내용:
     * - 기본 정보: 사용자명, 가입일
     * - 활동 통계: 게시물 수, 댓글 수, 받은 좋아요 수
     * - 작성한 게시물 목록 (최신순, 페이징)
     *
     * @param username URL 경로 변수 — 조회할 사용자명
     * @param page     게시물 목록 페이지 번호 (0부터 시작, 기본값 0)
     */
    @GetMapping("/users/{username}")
    public String profile(@PathVariable String username,
                          @RequestParam(defaultValue = "0") int page,
                          Model model) {
        // 프로필 정보 (통계 포함)
        model.addAttribute("profile", userService.getProfile(username));
        // 이 사용자가 작성한 게시물 목록 (페이지당 10개)
        model.addAttribute("posts", postService.getPostsByAuthor(username, page, 10));
        return "users/profile"; // templates/users/profile.html
    }
}
