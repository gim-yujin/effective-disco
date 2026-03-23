package com.effectivedisco.loadtest;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")
public class LoadTestBottleneckProfilingAspect {

    private final LoadTestMetricsService loadTestMetricsService;
    private final LoadTestSqlProfiler sqlProfiler;

    @Around("execution(* com.effectivedisco.service.CommentService.createComment(..))")
    public Object profileCommentCreate(ProceedingJoinPoint joinPoint) throws Throwable {
        return profile("comment.create", true, joinPoint);
    }

    @Around("execution(* com.effectivedisco.service.NotificationService.storeNotificationAfterCommit(..))")
    public Object profileNotificationStore(ProceedingJoinPoint joinPoint) throws Throwable {
        return profile("notification.store", true, joinPoint);
    }

    @Around("""
            execution(* com.effectivedisco.service.PostService.getPosts(int, int, String, String, String)) ||
            execution(* com.effectivedisco.service.PostService.getPosts(int, int, String, String, String, String))
            """)
    public Object profilePostList(ProceedingJoinPoint joinPoint) throws Throwable {
        return profile("post.list", false, joinPoint);
    }

    private Object profile(String profileName,
                           boolean transactionalWindow,
                           ProceedingJoinPoint joinPoint) throws Throwable {
        LoadTestSqlProfiler.ScopeToken scope = sqlProfiler.beginScope();
        long startedAt = System.nanoTime();
        try {
            return joinPoint.proceed();
        } finally {
            long wallNanos = System.nanoTime() - startedAt;
            LoadTestSqlProfiler.SqlProfileSnapshot sqlSnapshot = sqlProfiler.endScope(scope);

            // 문제 해결:
            // pool 포화 분석은 "요청이 느리다"만으로는 부족하다.
            // 대상 메서드별로 벽시계 시간, SQL 시간, SQL 문 수, 트랜잭션 길이를
            // 같은 샘플로 묶어야 댓글 작성/알림 저장/목록 조회 중 어디가 병목인지 구분할 수 있다.
            loadTestMetricsService.recordBottleneckSample(
                    profileName,
                    wallNanos,
                    sqlSnapshot.sqlStatementCount(),
                    sqlSnapshot.sqlExecutionNanos(),
                    transactionalWindow ? wallNanos : 0L,
                    transactionalWindow
            );
        }
    }
}
