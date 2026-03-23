package com.effectivedisco.security;

import com.effectivedisco.loadtest.LoadTestMetricsService;
import com.effectivedisco.loadtest.LoadTestStepProfiler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Service
public class CachedJwtUserDetailsService {

    private final CustomUserDetailsService delegate;
    private final LoadTestMetricsService loadTestMetricsService;
    private final LoadTestStepProfiler loadTestStepProfiler;
    private final long cacheTtlNanos;
    private final LongSupplier nanoTimeSupplier;
    private final ConcurrentHashMap<String, CachedUserDetails> cache = new ConcurrentHashMap<>();

    @Autowired
    public CachedJwtUserDetailsService(CustomUserDetailsService delegate,
                                       LoadTestMetricsService loadTestMetricsService,
                                       LoadTestStepProfiler loadTestStepProfiler,
                                       @Value("${app.security.jwt-user-details-cache-ttl:PT5S}") Duration cacheTtl) {
        this(delegate, loadTestMetricsService, loadTestStepProfiler, cacheTtl, System::nanoTime);
    }

    CachedJwtUserDetailsService(CustomUserDetailsService delegate,
                                LoadTestMetricsService loadTestMetricsService,
                                LoadTestStepProfiler loadTestStepProfiler,
                                Duration cacheTtl,
                                LongSupplier nanoTimeSupplier) {
        this.delegate = Objects.requireNonNull(delegate);
        this.loadTestMetricsService = Objects.requireNonNull(loadTestMetricsService);
        this.loadTestStepProfiler = Objects.requireNonNull(loadTestStepProfiler);
        this.cacheTtlNanos = Math.max(0L, Objects.requireNonNull(cacheTtl).toNanos());
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier);
    }

    /**
     * 문제 해결:
     * JWT API 요청은 같은 사용자의 토큰을 짧은 시간 동안 반복해서 보낸다.
     * 매 요청마다 DB로 UserDetails 를 다시 읽으면 서비스 로직과 무관하게 pool이 먼저 고갈된다.
     * 짧은 TTL의 로컬 캐시로 hit는 메모리에서 처리하고, miss만 DB/profiler에 기록해
     * 인증 조회 병목을 따로 계측하면서도 정지/권한 변경의 stale window 는 짧게 제한한다.
     */
    public UserDetails loadUserByUsername(String username) {
        long now = nanoTimeSupplier.getAsLong();
        CachedUserDetails cachedUserDetails = cache.get(username);
        if (cachedUserDetails != null && !cachedUserDetails.isExpired(now)) {
            loadTestMetricsService.recordJwtAuthCacheHit();
            return cachedUserDetails.userDetails();
        }

        return cache.compute(username, (key, existing) -> refreshIfExpired(username, existing)).userDetails();
    }

    private CachedUserDetails refreshIfExpired(String username, CachedUserDetails existing) {
        long now = nanoTimeSupplier.getAsLong();
        if (existing != null && !existing.isExpired(now)) {
            loadTestMetricsService.recordJwtAuthCacheHit();
            return existing;
        }

        loadTestMetricsService.recordJwtAuthCacheMiss();
        UserDetails loadedUserDetails = loadTestStepProfiler.profile(
                "jwt.auth.load-user.db",
                false,
                () -> delegate.loadUserByUsername(username)
        );
        return new CachedUserDetails(loadedUserDetails, now + cacheTtlNanos);
    }

    private record CachedUserDetails(UserDetails userDetails, long expiresAtNanos) {
        private boolean isExpired(long nowNanos) {
            return nowNanos >= expiresAtNanos;
        }
    }
}
