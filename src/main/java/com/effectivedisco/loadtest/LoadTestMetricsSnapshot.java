package com.effectivedisco.loadtest;

import java.util.List;

/**
 * 문제 해결:
 * k6 결과만으로는 서버 내부 DB pool 압력과 duplicate-key 충돌 누적치를 함께 보기 어렵다.
 * 부하 종료 직후 이 스냅샷을 저장하면 클라이언트 지연시간과 서버 내부 병목을 같은 실행 기준으로 비교할 수 있다.
 */
public record LoadTestMetricsSnapshot(
        long duplicateKeyConflicts,
        long dbPoolTimeouts,
        long jwtAuthCacheHits,
        long jwtAuthCacheMisses,
        int currentActiveConnections,
        int currentIdleConnections,
        int currentTotalConnections,
        int currentThreadsAwaitingConnection,
        int maxActiveConnections,
        int maxIdleConnections,
        int maxTotalConnections,
        int maxThreadsAwaitingConnection,
        List<LoadTestBottleneckProfileSnapshot> bottleneckProfiles,
        PostgresLoadTestSnapshot postgresSnapshot
) {
}
