package com.effectivedisco.service;

import com.effectivedisco.event.NotificationRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 문제 해결:
 * 알림 생성은 본문 트랜잭션과 분리해 AFTER_COMMIT 에서만 실행한다.
 * 이렇게 해야 게시물/댓글/쪽지 저장이 실제로 성공했을 때만 알림과 SSE가 생성된다.
 */
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationRequested(NotificationRequestedEvent event) {
        notificationService.storeNotificationAfterCommit(event);
    }
}
