package com.effectivedisco.controller.web;

import com.effectivedisco.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
     *
     * q 접두사에 따라 검색 유형이 결정된다.
     * - "#태그명" → 태그 검색 (findByTagName)
     * - "@작성자명" → 작성자 검색 (getPostsByAuthor)
     * - 그 외 → 제목/내용/작성자 키워드 검색 (searchByKeyword)
     */
    @GetMapping
    public String search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        String type = "keyword";
        String searchTarget = q;

        if (!q.isBlank()) {
            if (q.startsWith("#")) {
                type = "tag";
                searchTarget = q.substring(1).trim();
                model.addAttribute("posts",
                        postService.getPosts(page, 20, null, searchTarget, null));

            } else if (q.startsWith("@")) {
                type = "author";
                searchTarget = q.substring(1).trim();
                try {
                    model.addAttribute("posts",
                            postService.getPostsByAuthor(searchTarget, page, 20));
                } catch (UsernameNotFoundException e) {
                    model.addAttribute("posts", Page.empty());
                    model.addAttribute("userNotFound", true);
                }

            } else {
                model.addAttribute("posts",
                        postService.getPosts(page, 20, q, null, null));
            }
        } else {
            model.addAttribute("posts", null);
        }

        model.addAttribute("q", q);
        model.addAttribute("type", type);
        model.addAttribute("searchTarget", searchTarget);
        model.addAttribute("popularTags", postService.getPopularTagNames(15));
        return "search/results";
    }
}
