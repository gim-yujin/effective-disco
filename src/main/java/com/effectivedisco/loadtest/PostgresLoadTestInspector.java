package com.effectivedisco.loadtest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;

@Component
public class PostgresLoadTestInspector {

    private static final String UNKNOWN_DATABASE = "unknown";
    private static final String ACTIVITY_SQL = """
            SELECT
                COUNT(*) FILTER (WHERE state = 'active')::int AS active_sessions,
                COUNT(*) FILTER (
                    WHERE state <> 'idle'
                    AND wait_event_type IS NOT NULL
                )::int AS waiting_sessions,
                COUNT(*) FILTER (
                    WHERE state <> 'idle'
                    AND wait_event_type = 'Lock'
                )::int AS lock_waiting_sessions,
                COUNT(*) FILTER (
                    WHERE xact_start IS NOT NULL
                    AND state <> 'idle'
                    AND EXTRACT(EPOCH FROM (clock_timestamp() - xact_start)) * 1000 >= ?
                )::int AS long_running_transactions,
                COUNT(*) FILTER (
                    WHERE query_start IS NOT NULL
                    AND state = 'active'
                    AND EXTRACT(EPOCH FROM (clock_timestamp() - query_start)) * 1000 >= ?
                )::int AS long_running_queries,
                COALESCE(MAX(
                    CASE
                        WHEN xact_start IS NOT NULL AND state <> 'idle'
                        THEN (EXTRACT(EPOCH FROM (clock_timestamp() - xact_start)) * 1000)::bigint
                    END
                ), 0) AS longest_transaction_ms,
                COALESCE(MAX(
                    CASE
                        WHEN query_start IS NOT NULL AND state = 'active'
                        THEN (EXTRACT(EPOCH FROM (clock_timestamp() - query_start)) * 1000)::bigint
                    END
                ), 0) AS longest_query_ms
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND pid <> pg_backend_pid()
              AND (? = '' OR application_name = ?)
            """;
    private static final String WAIT_EVENT_SQL = """
            SELECT
                wait_event_type,
                wait_event,
                COUNT(*)::int AS session_count
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND pid <> pg_backend_pid()
              AND state <> 'idle'
              AND wait_event_type IS NOT NULL
              AND (? = '' OR application_name = ?)
            GROUP BY wait_event_type, wait_event
            ORDER BY session_count DESC, wait_event_type, wait_event
            LIMIT ?
            """;
    private static final String ACTIVE_QUERY_SQL = """
            SELECT
                (EXTRACT(EPOCH FROM (clock_timestamp() - query_start)) * 1000)::bigint AS runtime_ms,
                COALESCE(state, 'unknown') AS state,
                COALESCE(wait_event_type, 'none') AS wait_event_type,
                COALESCE(wait_event, 'none') AS wait_event,
                LEFT(REGEXP_REPLACE(COALESCE(query, ''), '\\s+', ' ', 'g'), 180) AS query_text
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND pid <> pg_backend_pid()
              AND query_start IS NOT NULL
              AND state <> 'idle'
              AND (? = '' OR application_name = ?)
            ORDER BY query_start ASC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final String applicationName;
    private final long longRunningTransactionThresholdMs;
    private final long longRunningQueryThresholdMs;
    private final int waitEventLimit;
    private final int slowQueryLimit;

    public PostgresLoadTestInspector(JdbcTemplate jdbcTemplate,
                                     DataSource dataSource,
                                     @Value("${app.load-test.postgres.application-name:effective-disco-loadtest}") String applicationName,
                                     @Value("${app.load-test.postgres.long-running-transaction-threshold-ms:500}") long longRunningTransactionThresholdMs,
                                     @Value("${app.load-test.postgres.long-running-query-threshold-ms:500}") long longRunningQueryThresholdMs,
                                     @Value("${app.load-test.postgres.wait-event-limit:5}") int waitEventLimit,
                                     @Value("${app.load-test.postgres.slow-query-limit:5}") int slowQueryLimit) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.applicationName = applicationName;
        this.longRunningTransactionThresholdMs = longRunningTransactionThresholdMs;
        this.longRunningQueryThresholdMs = longRunningQueryThresholdMs;
        this.waitEventLimit = waitEventLimit;
        this.slowQueryLimit = slowQueryLimit;
    }

    public PostgresLoadTestSnapshot snapshot() {
        String databaseProduct = detectDatabaseProduct();
        if (!isPostgres(databaseProduct)) {
            return PostgresLoadTestSnapshot.unavailable("not-postgresql", databaseProduct, applicationName);
        }

        try {
            ActivityRollup activityRollup = jdbcTemplate.queryForObject(
                    ACTIVITY_SQL,
                    (rs, rowNum) -> new ActivityRollup(
                            rs.getInt("active_sessions"),
                            rs.getInt("waiting_sessions"),
                            rs.getInt("lock_waiting_sessions"),
                            rs.getInt("long_running_transactions"),
                            rs.getInt("long_running_queries"),
                            rs.getLong("longest_transaction_ms"),
                            rs.getLong("longest_query_ms")
                    ),
                    longRunningTransactionThresholdMs,
                    longRunningQueryThresholdMs,
                    applicationName,
                    applicationName
            );

            if (activityRollup == null) {
                return PostgresLoadTestSnapshot.unavailable("activity-rollup-empty", databaseProduct, applicationName);
            }

            List<PostgresWaitEventSnapshot> topWaitEvents = jdbcTemplate.query(
                    WAIT_EVENT_SQL,
                    (rs, rowNum) -> new PostgresWaitEventSnapshot(
                            rs.getString("wait_event_type"),
                            rs.getString("wait_event"),
                            rs.getInt("session_count")
                    ),
                    applicationName,
                    applicationName,
                    waitEventLimit
            );

            List<PostgresActiveQuerySnapshot> slowActiveQueries = jdbcTemplate.query(
                    ACTIVE_QUERY_SQL,
                    (rs, rowNum) -> new PostgresActiveQuerySnapshot(
                            rs.getLong("runtime_ms"),
                            rs.getString("state"),
                            rs.getString("wait_event_type"),
                            rs.getString("wait_event"),
                            rs.getString("query_text")
                    ),
                    applicationName,
                    applicationName,
                    slowQueryLimit
            );

            return new PostgresLoadTestSnapshot(
                    true,
                    "ok",
                    databaseProduct,
                    applicationName,
                    activityRollup.activeSessions(),
                    activityRollup.waitingSessions(),
                    activityRollup.lockWaitingSessions(),
                    activityRollup.longRunningTransactions(),
                    activityRollup.longRunningQueries(),
                    activityRollup.longestTransactionMs(),
                    activityRollup.longestQueryMs(),
                    topWaitEvents,
                    slowActiveQueries
            );
        } catch (DataAccessException exception) {
            // 문제 해결:
            // pg_stat_activity 접근 권한이나 DB 종류 차이 때문에 계측이 실패해도
            // loadtest 본체가 멈추면 안 된다. unavailable 스냅샷으로 내려
            // "계측 실패"와 "실제 wait 없음"을 구분 가능하게 한다.
            return PostgresLoadTestSnapshot.unavailable(
                    simplifyFailureReason(exception),
                    databaseProduct,
                    applicationName
            );
        }
    }

    private String detectDatabaseProduct() {
        try {
            return jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName()
            );
        } catch (DataAccessException exception) {
            return UNKNOWN_DATABASE;
        }
    }

    private boolean isPostgres(String databaseProduct) {
        return databaseProduct != null
                && databaseProduct.toLowerCase(Locale.ROOT).contains("postgres");
    }

    private String simplifyFailureReason(DataAccessException exception) {
        String simpleName = exception.getClass().getSimpleName();
        return (simpleName == null || simpleName.isBlank()) ? "data-access-error" : simpleName;
    }

    private record ActivityRollup(
            int activeSessions,
            int waitingSessions,
            int lockWaitingSessions,
            int longRunningTransactions,
            int longRunningQueries,
            long longestTransactionMs,
            long longestQueryMs
    ) {
    }
}
