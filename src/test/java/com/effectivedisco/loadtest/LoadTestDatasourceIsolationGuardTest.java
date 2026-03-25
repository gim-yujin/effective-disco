package com.effectivedisco.loadtest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoadTestDatasourceIsolationGuardTest {

    @Test
    void extractDatabaseName_parsesPostgresJdbcUrl() {
        assertThat(LoadTestDatasourceIsolationGuard.extractDatabaseName(
                "jdbc:postgresql://localhost:5432/effectivedisco_loadtest?ssl=false"))
                .isEqualTo("effectivedisco_loadtest");
    }

    @Test
    void validateIsolation_allowsRequiredDatabaseName() {
        LoadTestDatasourceIsolationGuard guard = new LoadTestDatasourceIsolationGuard(
                "jdbc:postgresql://localhost:5432/effectivedisco_loadtest",
                "effectivedisco_loadtest"
        );

        guard.validateIsolation();
    }

    @Test
    void validateIsolation_rejectsSharedDevelopmentDatabase() {
        LoadTestDatasourceIsolationGuard guard = new LoadTestDatasourceIsolationGuard(
                "jdbc:postgresql://localhost:5432/effectivedisco",
                "effectivedisco_loadtest"
        );

        assertThatThrownBy(guard::validateIsolation)
                .as("문제 해결 검증: loadtest 프로필이 기본 개발 DB를 바라보면 기동을 막아야 한다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("effectivedisco_loadtest")
                .hasMessageContaining("effectivedisco");
    }
}
