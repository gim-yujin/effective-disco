package com.effectivedisco.loadtest;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class LoadTestInstrumentedDataSourceTest {

    HikariDataSource dataSource;
    LoadTestSqlProfiler sqlProfiler;
    LoadTestInstrumentedDataSource instrumentedDataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:loadtest-proxy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        sqlProfiler = new LoadTestSqlProfiler();
        instrumentedDataSource = new LoadTestInstrumentedDataSource(dataSource, sqlProfiler);

        try (Connection connection = instrumentedDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("create table sample_metrics (id bigint primary key, name varchar(100))");
        }
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void sqlScope_recordsExecutedStatementCountAndTime() throws Exception {
        LoadTestSqlProfiler.ScopeToken scope = sqlProfiler.beginScope();

        try (Connection connection = instrumentedDataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "insert into sample_metrics(id, name) values (?, ?)");
             PreparedStatement select = connection.prepareStatement(
                     "select count(*) from sample_metrics")) {
            insert.setLong(1, 1L);
            insert.setString(2, "loadtest");
            insert.executeUpdate();

            try (ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getLong(1)).isEqualTo(1L);
            }
        }

        LoadTestSqlProfiler.SqlProfileSnapshot snapshot = sqlProfiler.endScope(scope);

        assertThat(snapshot.sqlStatementCount())
                .as("문제 해결 검증: JDBC 프록시는 scope 안에서 실행된 SQL 문 수를 세어야 한다")
                .isEqualTo(2);
        assertThat(snapshot.sqlExecutionNanos()).isPositive();
    }
}
