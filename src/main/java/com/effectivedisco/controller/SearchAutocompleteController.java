package com.effectivedisco.controller;

import com.effectivedisco.service.SearchService;
import com.effectivedisco.service.SearchService.AutocompleteSuggestion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 검색 자동완성 REST API 컨트롤러.
 *
 * GET /api/search/autocomplete?q={query}
 */
@Tag(name = "Search", description = "검색 자동완성 API")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchAutocompleteController {

    private final SearchService searchService;

    @Operation(summary = "검색 자동완성",
               description = "입력 접두사에 따라 태그(#)·사용자(@)·혼합 추천을 반환한다.")
    @GetMapping("/autocomplete")
    public ResponseEntity<List<AutocompleteSuggestion>> autocomplete(
            @RequestParam(defaultValue = "") String q) {
        return ResponseEntity.ok(searchService.autocomplete(q));
    }
}
