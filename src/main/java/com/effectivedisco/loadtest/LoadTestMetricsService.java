package com.effectivedisco.loadtest;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LoadTestMetricsService {

    private static final String DUPLICATE_KEY_SQL_STATE = "23505";

    private final DataSource dataSource;
    private final AtomicLong duplicateKeyConflicts = new AtomicLong();
    private final AtomicLong dbPoolTimeouts = new AtomicLong();
    private final AtomicInteger maxActiveConnections = new AtomicInteger();
    private final AtomicInteger maxIdleConnections = new AtomicInteger();
    private final AtomicInteger maxTotalConnections = new AtomicInteger();
    private final AtomicInteger maxThreadsAwaitingConnection = new AtomicInteger();

    public LoadTestMetricsService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 문제 해결:
     * duplicate-key 예외가 실제로 몇 번 났는지 누적해야
     * 멱등 write 경로가 고트래픽에서 정말 안전한지 수치로 확인할 수 있다.
     */
    public void recordDuplicateKeyConflictIfDetected(DataIntegrityViolationException exception) {
        if (isDuplicateKeyViolation(exception)) {
            duplicateKeyConflicts.incrementAndGet();
        }
    }

    /**
     * 문제 해결:
     * 응답 지연만 보면 DB pool 대기와 애플리케이션 병목을 구분하기 어렵다.
     * Hikari timeout 패턴을 별도 카운터로 누적해 pool exhaustion 여부를 바로 읽을 수 있게 한다.
     */
    public void recordDbPoolTimeoutIfDetected(RuntimeException exception) {
        if (isDbPoolTimeout(exception)) {
            dbPoolTimeouts.incrementAndGet();
        }
    }

    public void samplePoolState() {
        PoolState state = readPoolState();
        updateMax(maxActiveConnections, state.activeConnections());
        updateMax(maxIdleConnections, state.idleConnections());
        updateMax(maxTotalConnections, state.totalConnections());
        updateMax(maxThreadsAwaitingConnection, state.threadsAwaitingConnection());
    }

    public synchronized LoadTestMetricsSnapshot reset() {
        duplicateKeyConflicts.set(0);
        dbPoolTimeouts.set(0);
        maxActiveConnections.set(0);
        maxIdleConnections.set(0);
        maxTotalConnections.set(0);
        maxThreadsAwaitingConnection.set(0);
        samplePoolState();
        return snapshot();
    }

    public LoadTestMetricsSnapshot snapshot() {
        PoolState state = readPoolState();
        updateMax(maxActiveConnections, state.activeConnections());
        updateMax(maxIdleConnections, state.idleConnections());
        updateMax(maxTotalConnections, state.totalConnections());
        updateMax(maxThreadsAwaitingConnection, state.threadsAwaitingConnection());

        return new LoadTestMetricsSnapshot(
                duplicateKeyConflicts.get(),
                dbPoolTimeouts.get(),
                state.activeConnections(),
                state.idleConnections(),
                state.totalConnections(),
                state.threadsAwaitingConnection(),
                maxActiveConnections.get(),
                maxIdleConnections.get(),
                maxTotalConnections.get(),
                maxThreadsAwaitingConnection.get()
        );
    }

    boolean isDuplicateKeyViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && DUPLICATE_KEY_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }

            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("duplicate key")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    boolean isDbPoolTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLTransientConnectionException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("connection is not available")
                        || normalized.contains("request timed out")
                        || normalized.contains("timeout after")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private PoolState readPoolState() {
        HikariPoolMXBean pool = getPoolMxBean();
        if (pool == null) {
            return PoolState.empty();
        }

        return new PoolState(
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection()
        );
    }

    private HikariPoolMXBean getPoolMxBean() {
        try {
            HikariDataSource hikariDataSource = unwrapHikariDataSource();
            return hikariDataSource != null ? hikariDataSource.getHikariPoolMXBean() : null;
        } catch (SQLException ignored) {
            return null;
        }
    }

    private HikariDataSource unwrapHikariDataSource() throws SQLException {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            return hikariDataSource;
        }
        return dataSource.isWrapperFor(HikariDataSource.class)
                ? dataSource.unwrap(HikariDataSource.class)
                : null;
    }

    private void updateMax(AtomicInteger target, int candidate) {
        target.accumulateAndGet(candidate, Math::max);
    }

    private record PoolState(
            int activeConnections,
            int idleConnections,
            int totalConnections,
            int threadsAwaitingConnection
    ) {
        private static PoolState empty() {
            return new PoolState(0, 0, 0, 0);
        }
    }
}
