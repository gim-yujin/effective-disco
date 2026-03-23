package com.effectivedisco.loadtest;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 문제 해결:
 * DB pool 대기 스파이크는 테스트 종료 시점의 단일 스냅샷으로는 놓치기 쉽다.
 * 짧은 주기로 풀 상태를 샘플링해 최대 active/awaiting 값을 부하 전체 구간에서 보존한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")
public class LoadTestMetricsSampler {

    private final LoadTestMetricsService loadTestMetricsService;

    @Scheduled(fixedDelayString = "${app.load-test.sampling-interval-ms:250}")
    public void sample() {
        loadTestMetricsService.samplePoolState();
    }
}
