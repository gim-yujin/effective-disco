package com.effectivedisco.event;

import com.effectivedisco.domain.NotificationType;

/**
 * 문제 해결:
 * 핵심 비즈니스 트랜잭션 안에서 바로 알림 row를 저장하거나 SSE를 보내면
 * 원 트랜잭션이 롤백될 때도 잘못된 알림/뱃지가 노출될 수 있다.
 * 알림 요청을 값 객체 이벤트로 분리해 "원 트랜잭션 커밋 후" 별도 흐름에서 처리한다.
 */
public record NotificationRequestedEvent(
        String recipientUsername,
        NotificationType type,
        String message,
        String link
) {
}
