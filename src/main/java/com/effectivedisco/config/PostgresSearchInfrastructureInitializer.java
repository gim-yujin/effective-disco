package com.effectivedisco.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PostgreSQL 검색 인프라 초기화.
 *
 * FTS/pg_trgm 는 PostgreSQL 전용 최적화이므로 H2 테스트나 다른 DB 에서는 건드리지 않는다.
 */
@Slf4j
@Component
public class PostgresSearchInfrastructureInitializer implements ApplicationRunner {

    private static final String CREATE_TRIGRAM_EXTENSION_SQL = "CREATE EXTENSION IF NOT EXISTS pg_trgm";
    private static final String CREATE_POSTS_FTS_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_posts_search_fts
            ON posts
            USING GIN (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, '')))
            WHERE draft = false
            """;
    private static final String CREATE_USERS_USERNAME_TRGM_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_users_username_trgm
            ON users
            USING GIN (lower(username) gin_trgm_ops)
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresSearchInfrastructureInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isPostgres()) {
            return;
        }

        createIndexSafely(CREATE_POSTS_FTS_INDEX_SQL, "idx_posts_search_fts");

        boolean trigramAvailable = createExtensionSafely("pg_trgm", CREATE_TRIGRAM_EXTENSION_SQL);
        if (trigramAvailable) {
            createIndexSafely(CREATE_USERS_USERNAME_TRGM_INDEX_SQL, "idx_users_username_trgm");
        }
    }

    private boolean isPostgres() {
        try {
            String databaseProductName = jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName()
            );
            return databaseProductName != null
                    && databaseProductName.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (DataAccessException exception) {
            return false;
        }
    }

    private boolean createExtensionSafely(String extensionName, String sql) {
        try {
            jdbcTemplate.execute(sql);
            return true;
        } catch (DataAccessException exception) {
            // 문제 해결:
            // pg_trgm extension 생성은 성능 최적화 준비 단계다.
            // 권한 없는 환경에서 extension 생성 실패로 앱 전체 부팅이 막히면 검색 기능까지 죽는다.
            // warning 으로 남기고, username 검색은 기존 LIKE semantics 로 계속 동작하게 둔다.
            log.warn("Could not create PostgreSQL extension {}. Falling back without trigram index support.", extensionName, exception);
            return false;
        }
    }

    private void createIndexSafely(String sql, String indexName) {
        try {
            jdbcTemplate.execute(sql);
        } catch (DataAccessException exception) {
            // 문제 해결:
            // FTS/trigram index 생성은 read-path 최적화지만, 권한/DDL 정책 차이로 실패할 수 있다.
            // 인덱스가 없더라도 검색 correctness 는 유지되므로 startup 전체를 막지 않고 경고만 남긴다.
            log.warn("Could not create PostgreSQL search index {}. Search will work but stay slower.", indexName, exception);
        }
    }
}
