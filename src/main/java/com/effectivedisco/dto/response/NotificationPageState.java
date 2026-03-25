package com.effectivedisco.dto.response;

/**
 * 문제 해결:
 * notification.read-page transition 은 알림 본문/링크/type 이 아니라
 * "현재 페이지에 보이는 id 와 읽음 여부"만 있으면 된다.
 * read-page hot path 를 얇은 projection 으로 분리해 불필요한 row width 와 DTO materialize 비용을 줄인다.
 */
public record NotificationPageState(Long id, boolean read) {
}
