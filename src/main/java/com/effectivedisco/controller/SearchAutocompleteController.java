package com.effectivedisco.controller;

import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 검색 자동완성 REST API 컨트롤러.
 *
 * GET /api/search/autocomplete?q={query}
 *
 * 검색어 접두사에 따라 추천 목록을 반환한다.
 * - "#태그" → 태그 이름 추천 (# 접두사 포함)
 * - "@사용자" → 사용자명 추천 (@ 접두사 포함)
 * - 그 외 → 태그 + 사용자 혼합 추천
 */
@Tag(name = "Search", description = "검색 자동완성 API")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchAutocompleteController {

    private final TagRepository  tagRepository;
    private final UserRepository userRepository;

    /** 자동완성 최대 결과 수 */
    private static final int MAX_RESULTS = 10;

    @Operation(summary = "검색 자동완성",
               description = "입력 접두사에 따라 태그(#)·사용자(@)·혼합 추천을 반환한다.")
    @GetMapping("/autocomplete")
    public ResponseEntity<List<AutocompleteSuggestion>> autocomplete(
            @RequestParam(defaultValue = "") String q) {

        if (q.isBlank() || q.length() < 1) {
            return ResponseEntity.ok(List.of());
        }

        List<AutocompleteSuggestion> suggestions;

        if (q.startsWith("#")) {
            // 태그 검색
            String prefix = q.substring(1);
            suggestions = searchTags(prefix, MAX_RESULTS);
        } else if (q.startsWith("@")) {
            // 사용자 검색
            String prefix = q.substring(1);
            suggestions = searchUsers(prefix, MAX_RESULTS);
        } else {
            // 혼합: 태그 5건 + 사용자 5건
            suggestions = new ArrayList<>();
            suggestions.addAll(searchTags(q, MAX_RESULTS / 2));
            suggestions.addAll(searchUsers(q, MAX_RESULTS / 2));
        }

        return ResponseEntity.ok(suggestions);
    }

    /** 태그명 접두사 검색 */
    private List<AutocompleteSuggestion> searchTags(String prefix, int limit) {
        if (prefix.isBlank()) return List.of();
        return tagRepository.findTop10ByNameStartingWithIgnoreCaseOrderByNameAsc(prefix)
                .stream()
                .limit(limit)
                .map(tag -> new AutocompleteSuggestion("#" + tag.getName(), "tag"))
                .toList();
    }

    /** 사용자명 접두사 검색 */
    private List<AutocompleteSuggestion> searchUsers(String prefix, int limit) {
        if (prefix.isBlank()) return List.of();
        return userRepository.findUsernamesByPrefix(prefix, PageRequest.of(0, limit))
                .stream()
                .map(username -> new AutocompleteSuggestion("@" + username, "user"))
                .toList();
    }

    /**
     * 자동완성 추천 항목.
     *
     * @param value 추천 텍스트 (예: "#java", "@alice")
     * @param type  추천 유형 ("tag" 또는 "user")
     */
    public record AutocompleteSuggestion(String value, String type) {}
}
