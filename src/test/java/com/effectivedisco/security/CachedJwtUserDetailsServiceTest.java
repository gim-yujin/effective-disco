package com.effectivedisco.security;

import com.effectivedisco.loadtest.LoadTestMetricsService;
import com.effectivedisco.loadtest.NoOpLoadTestStepProfiler;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CachedJwtUserDetailsServiceTest {

    @Mock CustomUserDetailsService delegate;

    HikariDataSource dataSource;
    LoadTestMetricsService loadTestMetricsService;
    AtomicLong nowNanos;
    CachedJwtUserDetailsService cachedJwtUserDetailsService;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:jwt-user-cache;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setMaximumPoolSize(2);
        dataSource.setMinimumIdle(1);

        loadTestMetricsService = new LoadTestMetricsService(dataSource);
        nowNanos = new AtomicLong(1_000_000L);
        cachedJwtUserDetailsService = new CachedJwtUserDetailsService(
                delegate,
                loadTestMetricsService,
                new NoOpLoadTestStepProfiler(),
                Duration.ofSeconds(5),
                nowNanos::get
        );
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void loadUserByUsername_cacheHitAvoidsSecondDbLookup() {
        UserDetails userDetails = makeUserDetails("alice");
        given(delegate.loadUserByUsername("alice")).willReturn(userDetails);

        UserDetails first = cachedJwtUserDetailsService.loadUserByUsername("alice");
        nowNanos.addAndGet(Duration.ofSeconds(1).toNanos());
        UserDetails second = cachedJwtUserDetailsService.loadUserByUsername("alice");

        assertThat(first).isSameAs(second);
        verify(delegate, times(1)).loadUserByUsername("alice");
        assertThat(loadTestMetricsService.snapshot().jwtAuthCacheHits())
                .as("문제 해결 검증: 같은 JWT 사용자의 반복 API 요청은 캐시 hit 로 집계되어야 한다")
                .isEqualTo(1);
        assertThat(loadTestMetricsService.snapshot().jwtAuthCacheMisses()).isEqualTo(1);
    }

    @Test
    void loadUserByUsername_expiredEntryTriggersCacheMiss() {
        UserDetails firstUserDetails = makeUserDetails("alice");
        UserDetails refreshedUserDetails = makeUserDetails("alice");
        given(delegate.loadUserByUsername("alice")).willReturn(firstUserDetails, refreshedUserDetails);

        cachedJwtUserDetailsService.loadUserByUsername("alice");
        nowNanos.addAndGet(Duration.ofSeconds(6).toNanos());
        cachedJwtUserDetailsService.loadUserByUsername("alice");

        verify(delegate, times(2)).loadUserByUsername("alice");
        assertThat(loadTestMetricsService.snapshot().jwtAuthCacheHits()).isZero();
        assertThat(loadTestMetricsService.snapshot().jwtAuthCacheMisses()).isEqualTo(2);
    }

    private UserDetails makeUserDetails(String username) {
        return new User(username, "encoded-password", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
