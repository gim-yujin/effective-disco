package com.effectivedisco.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MentionParserTest {

    @Test
    void extract_basicMentions_returnsDistinctUsernames() {
        assertThat(MentionParser.extract("hi @alice 그리고 @bob 안녕"))
                .containsExactly("alice", "bob");
    }

    @Test
    void extract_duplicateMentions_returnsDistinct() {
        assertThat(MentionParser.extract("@alice @alice @alice"))
                .containsExactly("alice");
    }

    @Test
    void extract_preservesInputOrder() {
        assertThat(MentionParser.extract("@bob 먼저 그리고 @alice"))
                .containsExactly("bob", "alice");
    }

    @Test
    void extract_usernameTooShort_skipped() {
        assertThat(MentionParser.extract("hi @ab"))
                .isEmpty();
    }

    @Test
    void extract_minimumLengthThree_matches() {
        assertThat(MentionParser.extract("hi @abc"))
                .containsExactly("abc");
    }

    @Test
    void extract_usernameTooLong_onlyFirst20CharsMatch() {
        // username regex 는 3~20 자 범위만 캡처하므로 21자 초과 입력은 앞 20자에서 끊긴다.
        String twentyOne = "abcdefghijklmnopqrstu";
        assertThat(MentionParser.extract("@" + twentyOne))
                .containsExactly(twentyOne.substring(0, 20));
    }

    @Test
    void extract_withTrailingPunctuation_stopsAtBoundary() {
        assertThat(MentionParser.extract("@alice!"))
                .containsExactly("alice");
    }

    @Test
    void extract_underscoreAllowed_hyphenNot() {
        assertThat(MentionParser.extract("@alice_1-dash"))
                .containsExactly("alice_1");
    }

    @Test
    void extract_emailLikeString_capturesDomainPrefix() {
        // foo@bar.com 에서 "@bar" 가 매치됨. 도메인 TLD 는 regex 경계 밖.
        // 정책: 허용 — 현실적으로 email username 이 BBS username 과 겹치면 알림은 발행되지만
        // findExistingUsernames 에서 실사용자가 없으면 걸러진다.
        assertThat(MentionParser.extract("foo@bar.com"))
                .containsExactly("bar");
    }

    @Test
    void extract_nullContent_returnsEmpty() {
        Set<String> result = MentionParser.extract(null);
        assertThat(result).isEmpty();
    }

    @Test
    void extract_blankContent_returnsEmpty() {
        assertThat(MentionParser.extract("")).isEmpty();
        assertThat(MentionParser.extract("   ")).isEmpty();
    }

    @Test
    void extract_noMentions_returnsEmpty() {
        assertThat(MentionParser.extract("일반 텍스트만 있는 본문")).isEmpty();
    }
}
