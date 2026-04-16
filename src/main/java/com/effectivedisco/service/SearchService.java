package com.effectivedisco.service;

import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 검색 자동완성 비즈니스 로직.
 *
 * 검색어 접두사에 따라 추천 목록을 반환한다.
 * - "#태그" → 태그 이름 추천 (# 접두사 포함)
 * - "@사용자" → 사용자명 추천 (@ 접두사 포함)
 * - 그 외 → 태그 + 사용자 혼합 추천
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private final TagRepository  tagRepository;
    private final UserRepository userRepository;

    /** 자동완성 최대 결과 수 */
    private static final int MAX_RESULTS = 10;

    /**
     * 검색어에 따라 자동완성 추천 목록을 반환한다.
     *
     * @param query 검색 입력값
     * @return 추천 항목 목록 (빈 입력이면 빈 리스트)
     */
    public List<AutocompleteSuggestion> autocomplete(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        if (query.startsWith("#")) {
            String prefix = query.substring(1);
            return searchTags(prefix, MAX_RESULTS);
        } else if (query.startsWith("@")) {
            String prefix = query.substring(1);
            return searchUsers(prefix, MAX_RESULTS);
        } else {
            List<AutocompleteSuggestion> suggestions = new ArrayList<>();
            suggestions.addAll(searchTags(query, MAX_RESULTS / 2));
            suggestions.addAll(searchUsers(query, MAX_RESULTS / 2));
            return suggestions;
        }
    }

    private List<AutocompleteSuggestion> searchTags(String prefix, int limit) {
        if (prefix.isBlank()) return List.of();
        return tagRepository.findTop10ByNameStartingWithIgnoreCaseOrderByNameAsc(prefix)
                .stream()
                .limit(limit)
                .map(tag -> new AutocompleteSuggestion("#" + tag.getName(), "tag"))
                .toList();
    }

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
