package com.effectivedisco.loadtest;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 문제 해결:
 * loadtest 프로필이 개발 기본 DB와 같은 PostgreSQL 데이터베이스를 바라보면
 * 부하 테스트가 만든 row가 로컬 수동 확인과 다음 측정 결과를 계속 오염시킨다.
 * loadtest는 지정된 전용 DB 이름으로만 기동되게 막아 실수로 shared DB를 쓰는 상황을 차단한다.
 */
@Component
@Profile("loadtest")
@ConditionalOnProperty(name = "app.load-test.datasource.enforce-isolation", havingValue = "true", matchIfMissing = true)
public class LoadTestDatasourceIsolationGuard {

    private final String datasourceUrl;
    private final String requiredDatabaseName;

    public LoadTestDatasourceIsolationGuard(@Value("${spring.datasource.url:}") String datasourceUrl,
                                            @Value("${app.load-test.datasource.required-db-name:effectivedisco_loadtest}") String requiredDatabaseName) {
        this.datasourceUrl = datasourceUrl;
        this.requiredDatabaseName = requiredDatabaseName;
    }

    @PostConstruct
    void validateIsolation() {
        if (datasourceUrl == null || datasourceUrl.isBlank()) {
            throw new IllegalStateException("loadtest datasource url must not be blank");
        }

        if (!datasourceUrl.startsWith("jdbc:postgresql:")) {
            return;
        }

        String actualDatabaseName = extractDatabaseName(datasourceUrl);
        if (!requiredDatabaseName.equals(actualDatabaseName)) {
            throw new IllegalStateException(
                    "loadtest datasource must use dedicated database '" + requiredDatabaseName
                            + "' but was '" + actualDatabaseName + "' (" + datasourceUrl + ")"
            );
        }
    }

    static String extractDatabaseName(String jdbcUrl) {
        int lastSlash = jdbcUrl.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == jdbcUrl.length() - 1) {
            return "";
        }

        int queryIndex = jdbcUrl.indexOf('?', lastSlash + 1);
        if (queryIndex < 0) {
            return jdbcUrl.substring(lastSlash + 1);
        }
        return jdbcUrl.substring(lastSlash + 1, queryIndex);
    }
}
