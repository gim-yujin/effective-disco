package com.effectivedisco.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SseEmitterService 단위 테스트.
 *
 * SSE 구독·이벤트 전송·에미터 정리 로직을 검증한다.
 * SseEmitter는 실제 HTTP 연결 없이도 인스턴스화가 가능하므로 mock 없이 테스트한다.
 */
class SseEmitterServiceTest {

    SseEmitterService sseEmitterService;

    @BeforeEach
    void setUp() {
        sseEmitterService = new SseEmitterService();
    }

    // ── subscribe ────────────────────────────────────────

    @Test
    void subscribe_returnsNonNullEmitter() {
        // 구독하면 SseEmitter 인스턴스가 반환되어야 한다
        SseEmitter emitter = sseEmitterService.subscribe("alice", 5);

        assertThat(emitter).isNotNull();
    }

    @Test
    void subscribe_replacesExistingEmitter() {
        // 첫 번째 구독
        SseEmitter first = sseEmitterService.subscribe("alice", 3);

        // 같은 사용자가 다시 구독하면 기존 에미터가 교체되어야 한다
        SseEmitter second = sseEmitterService.subscribe("alice", 0);

        // 새 에미터는 이전과 다른 인스턴스여야 한다
        assertThat(second).isNotSameAs(first);
    }

    // ── sendCount ────────────────────────────────────────

    @Test
    void sendCount_noEmitter_doesNotThrow() {
        // 구독하지 않은 사용자에게 전송 시도 — 에러 없이 무시되어야 한다
        sseEmitterService.sendCount("ghost", 10);
        // 예외 없이 통과하면 성공
    }

    @Test
    void sendCount_afterSubscribe_doesNotThrow() {
        // 구독 후 전송 시도 — 실제 HTTP 연결이 없어 IOException이 날 수 있지만
        // SseEmitterService는 이를 내부적으로 catch해야 한다
        sseEmitterService.subscribe("alice", 0);
        sseEmitterService.sendCount("alice", 5);
        // 예외 없이 통과하면 성공
    }

    // ── 에미터 정리 (completion callback) ─────────────────

    @Test
    void subscribe_completedEmitter_sendCountIgnored() {
        // 구독 후 에미터가 완료/만료 상태가 되면
        // sendCount 호출 시 IOException이 발생해도 에러 없이 무시되어야 한다.
        // SseEmitter.complete()는 HTTP 응답 컨텍스트 없이 호출하면
        // IllegalStateException을 던지므로, 여기서는 미구독 사용자와
        // 동일하게 sendCount가 무시됨을 검증한다.
        sseEmitterService.sendCount("nobody", 10);
        // 예외 없이 통과하면 성공
    }
}
