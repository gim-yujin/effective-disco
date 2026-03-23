package com.effectivedisco.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PostgresLoadTestInspectorTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    DataSource dataSource;

    @Mock
    ResultSet resultSet;

    @Test
    void snapshot_onNonPostgres_returnsUnavailableSnapshot() {
        given(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class))).willReturn("H2");

        PostgresLoadTestInspector inspector = new PostgresLoadTestInspector(
                jdbcTemplate,
                dataSource,
                "effective-disco-loadtest",
                500L,
                500L,
                5,
                5
        );

        PostgresLoadTestSnapshot snapshot = inspector.snapshot();

        assertThat(snapshot.available())
                .as("문제 해결 검증: PostgreSQL 이 아닌 테스트 DB 에서는 계측 실패 대신 unavailable 로 내려야 한다")
                .isFalse();
        assertThat(snapshot.availabilityReason()).isEqualTo("not-postgresql");
    }

    @Test
    void snapshot_onPostgres_returnsWaitAndSlowQueryData() throws SQLException {
        given(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class))).willReturn("PostgreSQL");
        given(resultSet.getInt("active_sessions")).willReturn(4);
        given(resultSet.getInt("waiting_sessions")).willReturn(3);
        given(resultSet.getInt("lock_waiting_sessions")).willReturn(2);
        given(resultSet.getInt("long_running_transactions")).willReturn(1);
        given(resultSet.getInt("long_running_queries")).willReturn(2);
        given(resultSet.getLong("longest_transaction_ms")).willReturn(1400L);
        given(resultSet.getLong("longest_query_ms")).willReturn(1600L);
        given(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), anyLong(), anyLong(), anyString(), anyString()))
                .willAnswer(invocation -> {
                    RowMapper<?> rowMapper = invocation.getArgument(1);
                    return rowMapper.mapRow(resultSet, 0);
                });
        given(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), anyString(), anyInt()))
                .willAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("GROUP BY wait_event_type")) {
                        return List.of(new PostgresWaitEventSnapshot("Lock", "transactionid", 3));
                    }
                    return List.of(new PostgresActiveQuerySnapshot(1600L, "active", "Lock", "transactionid", "select * from posts"));
                });

        PostgresLoadTestInspector inspector = new PostgresLoadTestInspector(
                jdbcTemplate,
                dataSource,
                "effective-disco-loadtest",
                500L,
                500L,
                5,
                5
        );

        PostgresLoadTestSnapshot snapshot = inspector.snapshot();

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.waitingSessions()).isEqualTo(3);
        assertThat(snapshot.lockWaitingSessions()).isEqualTo(2);
        assertThat(snapshot.longestQueryMs()).isEqualTo(1600L);
        assertThat(snapshot.topWaitEvents()).hasSize(1);
        assertThat(snapshot.slowActiveQueries()).hasSize(1);
    }

    @Test
    void snapshot_onDataAccessFailure_returnsUnavailableSnapshot() {
        given(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class))).willReturn("PostgreSQL");
        given(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), anyLong(), anyLong(), anyString(), anyString()))
                .willThrow(new DataAccessResourceFailureException("pg stat unavailable"));

        PostgresLoadTestInspector inspector = new PostgresLoadTestInspector(
                jdbcTemplate,
                dataSource,
                "effective-disco-loadtest",
                500L,
                500L,
                5,
                5
        );

        PostgresLoadTestSnapshot snapshot = inspector.snapshot();

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.availabilityReason()).isEqualTo("DataAccessResourceFailureException");
    }
}
