package com.effectivedisco.loadtest;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")
class ActiveLoadTestStepProfiler implements LoadTestStepProfiler {

    private final LoadTestMetricsService loadTestMetricsService;
    private final LoadTestSqlProfiler sqlProfiler;

    @Override
    public <T> T profileChecked(String profileName,
                                boolean transactionalWindow,
                                ThrowingSupplier<T> supplier) throws Throwable {
        LoadTestSqlProfiler.ScopeToken scope = sqlProfiler.beginScope();
        long startedAt = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            long wallNanos = System.nanoTime() - startedAt;
            LoadTestSqlProfiler.SqlProfileSnapshot sqlSnapshot = sqlProfiler.endScope(scope);

            loadTestMetricsService.recordBottleneckSample(
                    profileName,
                    wallNanos,
                    sqlSnapshot.sqlStatementCount(),
                    sqlSnapshot.sqlExecutionNanos(),
                    transactionalWindow ? wallNanos : 0L,
                    transactionalWindow
            );
        }
    }
}
