package com.effectivedisco.controller.web;

import com.effectivedisco.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchWebController {

    private final PostService postService;

    /**
     * 전체 게시판 통합 검색 결과 페이지.
     * q 파라미터가 없거나 공백이면 빈 결과를 표시한다.
     */
    @GetMapping
    public String search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        if (!q.isBlank()) {
            // 전체 게시판에서 키워드 검색 (boardSlug = null)
            model.addAttribute("posts", postService.getPosts(page, 20, q, null, null));
        } else {
            model.addAttribute("posts", null);
        }

        model.addAttribute("q", q);
        model.addAttribute("popularTags", postService.getPopularTagNames(15));
        return "search/results";
    }
}
