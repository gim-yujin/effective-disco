package com.effectivedisco.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-Sent Events(SSE) 에미터 관리 서비스.
 *
 * 사용자별로 최대 1개의 SseEmitter를 ConcurrentHashMap에 보관한다.
 * 새 탭에서 연결이 들어오면 기존 연결을 먼저 종료하고 교체한다.
 *
 * 흐름:
 * 1. 브라우저 → GET /sse/notifications → subscribe() → SseEmitter 반환
 * 2. 알림 생성 → NotificationService → sendCount() → 에미터로 미읽음 수 push
 * 3. 브라우저 EventSource가 "unread-count" 이벤트를 수신해 뱃지를 갱신한다.
 *
 * 클러스터 환경에서는 Redis Pub/Sub 같은 외부 브로커가 필요하지만,
 * 단일 서버 구성에서는 이 인메모리 맵으로 충분하다.
 */
@Service
@Slf4j
public class SseEmitterService {

    /** 사용자명 → SseEmitter 매핑. 동시 접근을 위해 ConcurrentHashMap 사용. */
    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 사용자를 SSE 채널에 구독시킨다.
     *
     * 타임아웃을 0으로 설정해 서버 쪽에서 먼저 연결을 끊지 않는다.
     * 브라우저가 탭을 닫거나 네트워크가 끊어지면 onCompletion/onError 콜백이 실행된다.
     *
     * @param username 구독할 사용자명
     * @param initialCount 연결 직후 전송할 현재 미읽음 알림 수
     * @return 브라우저에 반환할 SseEmitter
     */
    public SseEmitter subscribe(String username, long initialCount) {
        // 기존 에미터가 있으면 먼저 종료 (새 탭 접속 시 이전 연결 정리)
        SseEmitter existing = emitters.get(username);
        if (existing != null) {
            existing.complete();
        }

        // 타임아웃 0 = 서버 측 타임아웃 없음
        SseEmitter emitter = new SseEmitter(0L);

        // 연결 종료 시 맵에서 제거 — 세 콜백 모두 등록해 누수 방지
        Runnable cleanup = () -> emitters.remove(username, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        emitters.put(username, emitter);

        // 연결 직후 현재 미읽음 수를 즉시 전송 (초기 뱃지 동기화)
        sendCountInternal(emitter, username, initialCount);

        log.debug("SSE 구독: {} (현재 미읽음 {})", username, initialCount);
        return emitter;
    }

    /**
     * 특정 사용자에게 미읽음 알림 수를 push한다.
     * 에미터가 없거나 전송에 실패하면 조용히 무시한다.
     *
     * @param username 수신자 사용자명
     * @param count    현재 미읽음 알림 수
     */
    public void sendCount(String username, long count) {
        SseEmitter emitter = emitters.get(username);
        if (emitter == null) return; // 연결 없음 → 무시
        sendCountInternal(emitter, username, count);
    }

    /* ── private ─────────────────────────────────────────────── */

    private void sendCountInternal(SseEmitter emitter, String username, long count) {
        try {
            // 이벤트명: "unread-count", 데이터: 숫자 문자열
            emitter.send(SseEmitter.event()
                    .name("unread-count")
                    .data(count));
        } catch (IOException e) {
            // 전송 실패 = 브라우저가 이미 연결을 끊었음 → 에미터 제거
            log.debug("SSE 전송 실패 ({}): {}", username, e.getMessage());
            emitters.remove(username, emitter);
        }
    }
}
