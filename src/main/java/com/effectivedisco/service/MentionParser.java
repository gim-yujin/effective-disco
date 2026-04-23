package com.effectivedisco.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 게시물/댓글 본문에서 @username 토큰을 추출한다.
 *
 * username 규칙은 SignupRequest 의 @Size(3,20) + 스키마 설명(영문·숫자·밑줄) 과 맞춘다.
 * 반환 집합은 LinkedHashSet 으로 입력 순서를 유지하면서 중복을 제거한다.
 */
public final class MentionParser {

    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_]{3,20})");

    private MentionParser() {}

    public static Set<String> extract(String content) {
        if (content == null || content.isBlank()) {
            return new LinkedHashSet<>();
        }
        Set<String> usernames = new LinkedHashSet<>();
        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            usernames.add(matcher.group(1));
        }
        return usernames;
    }
}
