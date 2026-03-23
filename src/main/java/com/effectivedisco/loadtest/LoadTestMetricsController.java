package com.effectivedisco.loadtest;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문제 해결:
 * k6가 끝난 직후 같은 실행의 서버 내부 지표를 자동 수집할 수 있어야
 * p95/p99와 DB pool 병목, duplicate-key 충돌을 한 번에 비교할 수 있다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/load-test")
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")
public class LoadTestMetricsController {

    private final LoadTestMetricsService loadTestMetricsService;

    @GetMapping("/metrics")
    public LoadTestMetricsSnapshot metrics() {
        return loadTestMetricsService.snapshot();
    }

    @PostMapping("/reset")
    public LoadTestMetricsSnapshot reset() {
        return loadTestMetricsService.reset();
    }
}
