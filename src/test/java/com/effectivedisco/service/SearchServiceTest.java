package com.effectivedisco.service;

import com.effectivedisco.domain.Tag;
import com.effectivedisco.repository.TagRepository;
import com.effectivedisco.repository.UserRepository;
import com.effectivedisco.service.SearchService.AutocompleteSuggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock TagRepository  tagRepository;
    @Mock UserRepository userRepository;

    @InjectMocks SearchService searchService;

    // ── null / blank ──────────────────────────────────────

    @Test
    void autocomplete_null_returnsEmpty() {
        assertThat(searchService.autocomplete(null)).isEmpty();
    }

    @Test
    void autocomplete_blank_returnsEmpty() {
        assertThat(searchService.autocomplete("   ")).isEmpty();
    }

    // ── # tag search ──────────────────────────────────────

    @Test
    void autocomplete_hashPrefix_searchesTags() {
        given(tagRepository.findTop10ByNameStartingWithIgnoreCaseOrderByNameAsc("ja"))
                .willReturn(List.of(new Tag("java"), new Tag("javascript")));

        List<AutocompleteSuggestion> result = searchService.autocomplete("#ja");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).value()).isEqualTo("#java");
        assertThat(result.get(0).type()).isEqualTo("tag");
        assertThat(result.get(1).value()).isEqualTo("#javascript");
        verify(userRepository, never()).findUsernamesByPrefix(any(), any());
    }

    @Test
    void autocomplete_hashOnly_returnsEmpty() {
        // "#" alone → prefix is blank → searchTags returns empty
        List<AutocompleteSuggestion> result = searchService.autocomplete("#");

        assertThat(result).isEmpty();
    }

    // ── @ user search ─────────────────────────────────────

    @Test
    void autocomplete_atPrefix_searchesUsers() {
        given(userRepository.findUsernamesByPrefix(eq("al"), any()))
                .willReturn(List.of("alice", "alex"));

        List<AutocompleteSuggestion> result = searchService.autocomplete("@al");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).value()).isEqualTo("@alice");
        assertThat(result.get(0).type()).isEqualTo("user");
        verify(tagRepository, never()).findTop10ByNameStartingWithIgnoreCaseOrderByNameAsc(any());
    }

    @Test
    void autocomplete_atOnly_returnsEmpty() {
        List<AutocompleteSuggestion> result = searchService.autocomplete("@");

        assertThat(result).isEmpty();
    }

    // ── mixed search ──────────────────────────────────────

    @Test
    void autocomplete_plainQuery_searchesBothTagsAndUsers() {
        given(tagRepository.findTop10ByNameStartingWithIgnoreCaseOrderByNameAsc("spring"))
                .willReturn(List.of(new Tag("spring"), new Tag("springboot")));
        given(userRepository.findUsernamesByPrefix(eq("spring"), any()))
                .willReturn(List.of("springfan"));

        List<AutocompleteSuggestion> result = searchService.autocomplete("spring");

        assertThat(result).extracting(AutocompleteSuggestion::type)
                .containsExactly("tag", "tag", "user");
    }
}
