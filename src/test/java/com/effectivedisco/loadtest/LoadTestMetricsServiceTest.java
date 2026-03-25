package com.effectivedisco.loadtest;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class LoadTestMetricsServiceTest {

    @Mock
    PostgresLoadTestInspector postgresLoadTestInspector;

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

        lenient().when(postgresLoadTestInspector.snapshot())
                .thenReturn(PostgresLoadTestSnapshot.unavailable("not-postgresql", "H2", "effective-disco-loadtest"));

        loadTestMetricsService = new LoadTestMetricsService(dataSource, postgresLoadTestInspector);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void duplicateKeyViolation_incrementsConflictCounter() {
        loadTestMetricsService.recordDuplicateKeyConflictIfDetected(
                new DataIntegrityViolationException(
                        "duplicate",
                        new SQLException("duplicate key value violates unique constraint \"uk_post_like\"", "23505")
                ),
                "POST /api/posts/{id}/like"
        );

        LoadTestMetricsSnapshot snapshot = loadTestMetricsService.snapshot();

        assertThat(snapshot.duplicateKeyConflicts())
                .as("문제 해결 검증: duplicate-key 예외는 별도 카운터로 누적돼야 한다")
                .isEqualTo(1);
        assertThat(snapshot.duplicateKeyConflictProfiles())
                .singleElement()
                .satisfies(profile -> {
                    assertThat(profile.requestSignature()).isEqualTo("POST /api/posts/{id}/like");
                    assertThat(profile.constraintName()).isEqualTo("uk_post_like");
                    assertThat(profile.count()).isEqualTo(1);
                });
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
        loadTestMetricsService.recordJwtAuthCacheHit();
        loadTestMetricsService.recordJwtAuthCacheMiss();

        LoadTestMetricsSnapshot snapshot = loadTestMetricsService.reset();

        assertThat(snapshot.duplicateKeyConflicts()).isZero();
        assertThat(snapshot.duplicateKeyConflictProfiles()).isEmpty();
        assertThat(snapshot.dbPoolTimeouts()).isZero();
        assertThat(snapshot.jwtAuthCacheHits()).isZero();
        assertThat(snapshot.jwtAuthCacheMisses()).isZero();
    }

    @Test
    void jwtAuthCacheCounters_accumulateHitAndMissCounts() {
        loadTestMetricsService.recordJwtAuthCacheHit();
        loadTestMetricsService.recordJwtAuthCacheHit();
        loadTestMetricsService.recordJwtAuthCacheMiss();

        LoadTestMetricsSnapshot snapshot = loadTestMetricsService.snapshot();

        assertThat(snapshot.jwtAuthCacheHits())
                .as("문제 해결 검증: JWT 인증 캐시 hit/miss 는 인증 조회 병목을 분리해 볼 수 있게 누적돼야 한다")
                .isEqualTo(2);
        assertThat(snapshot.jwtAuthCacheMisses()).isEqualTo(1);
    }

    @Test
    void normalizeRequestSignature_replacesNumericPathSegments() {
        String signature = loadTestMetricsService.normalizeRequestSignature("post", "/api/posts/123/comments/456");

        assertThat(signature)
                .as("문제 해결 검증: duplicate-key 경로는 숫자 id를 일반화해 같은 write path를 한 그룹으로 봐야 한다")
                .isEqualTo("POST /api/posts/{id}/comments/{id}");
    }

    @Test
    void snapshot_includesPostgresInspectorOutput() {
        given(postgresLoadTestInspector.snapshot()).willReturn(
                new PostgresLoadTestSnapshot(
                        true,
                        "ok",
                        "PostgreSQL",
                        "effective-disco-loadtest",
                        3,
                        2,
                        1,
                        1,
                        2,
                        1400L,
                        1600L,
                        java.util.List.of(new PostgresWaitEventSnapshot("Lock", "transactionid", 2)),
                        java.util.List.of(new PostgresActiveQuerySnapshot(1600L, "active", "Lock", "transactionid", "select 1"))
                )
        );

        LoadTestMetricsSnapshot snapshot = loadTestMetricsService.snapshot();

        assertThat(snapshot.postgresSnapshot().available())
                .as("문제 해결 검증: loadtest 스냅샷은 PostgreSQL wait/slow-query 정보까지 함께 포함해야 한다")
                .isTrue();
        assertThat(snapshot.postgresSnapshot().waitingSessions()).isEqualTo(2);
        assertThat(snapshot.postgresSnapshot().slowActiveQueries()).hasSize(1);
    }

    @Test
    void bottleneckProfileSample_accumulatesWallSqlAndTransactionStats() {
        loadTestMetricsService.recordBottleneckSample(
                "comment.create",
                12_000_000L,
                4,
                6_000_000L,
                12_000_000L,
                true
        );
        loadTestMetricsService.recordBottleneckSample(
                "comment.create",
                18_000_000L,
                6,
                8_000_000L,
                18_000_000L,
                true
        );

        LoadTestBottleneckProfileSnapshot snapshot = loadTestMetricsService.snapshot()
                .bottleneckProfiles()
                .stream()
                .filter(profile -> profile.name().equals("comment.create"))
                .findFirst()
                .orElseThrow();

        assertThat(snapshot.sampleCount()).isEqualTo(2);
        assertThat(snapshot.maxSqlStatementCount()).isEqualTo(6);
        assertThat(snapshot.transactionObservedCount()).isEqualTo(2);
        assertThat(snapshot.averageWallTimeMs())
                .as("문제 해결 검증: 병목 프로파일은 대상 경로의 평균 벽시계 시간을 보여줘야 한다")
                .isEqualTo(15.0);
    }
}
