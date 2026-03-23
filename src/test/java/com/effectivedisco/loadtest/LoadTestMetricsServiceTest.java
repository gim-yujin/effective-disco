package com.effectivedisco.loadtest;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class LoadTestMetricsServiceTest {

    HikariDataSource dataSource;
    LoadTestMetricsService loadTestMetricsService;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:loadtest-metrics;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setMaximumPoolSize(2);
        dataSource.setMinimumIdle(1);

        loadTestMetricsService = new LoadTestMetricsService(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void duplicateKeyViolation_incrementsConflictCounter() {
        loadTestMetricsService.recordDuplicateKeyConflictIfDetected(
                new DataIntegrityViolationException("duplicate", new SQLException("duplicate key", "23505"))
        );

        assertThat(loadTestMetricsService.snapshot().duplicateKeyConflicts())
                .as("문제 해결 검증: duplicate-key 예외는 별도 카운터로 누적돼야 한다")
                .isEqualTo(1);
    }

    @Test
    void nonDuplicateViolation_doesNotIncrementConflictCounter() {
        loadTestMetricsService.recordDuplicateKeyConflictIfDetected(
                new DataIntegrityViolationException("fk", new SQLException("foreign key", "23503"))
        );

        assertThat(loadTestMetricsService.snapshot().duplicateKeyConflicts()).isZero();
    }

    @Test
    void dbPoolTimeout_incrementsTimeoutCounter() {
        loadTestMetricsService.recordDbPoolTimeoutIfDetected(
                new DataAccessResourceFailureException("pool timeout",
                        new SQLTransientConnectionException("Connection is not available, request timed out"))
        );

        assertThat(loadTestMetricsService.snapshot().dbPoolTimeouts())
                .as("문제 해결 검증: pool timeout 징후는 별도 카운터로 누적돼야 한다")
                .isEqualTo(1);
    }

    @Test
    void reset_clearsAccumulatedCounters() {
        loadTestMetricsService.recordDuplicateKeyConflictIfDetected(
                new DataIntegrityViolationException("duplicate", new SQLException("duplicate key", "23505"))
        );
        loadTestMetricsService.recordDbPoolTimeoutIfDetected(
                new DataAccessResourceFailureException("pool timeout",
                        new SQLTransientConnectionException("Connection is not available, request timed out"))
        );

        LoadTestMetricsSnapshot snapshot = loadTestMetricsService.reset();

        assertThat(snapshot.duplicateKeyConflicts()).isZero();
        assertThat(snapshot.dbPoolTimeouts()).isZero();
    }
}
