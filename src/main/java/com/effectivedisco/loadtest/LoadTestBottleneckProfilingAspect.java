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

    private final LoadTestStepProfiler loadTestStepProfiler;

    @Around("execution(* com.effectivedisco.service.CommentService.createComment(..))")
    public Object profileCommentCreate(ProceedingJoinPoint joinPoint) throws Throwable {
        return loadTestStepProfiler.profileChecked(
                "comment.create",
                true,
                (LoadTestStepProfiler.ThrowingSupplier<Object>) joinPoint::proceed
        );
    }

    @Around("execution(* com.effectivedisco.service.NotificationService.storeNotificationAfterCommit(..))")
    public Object profileNotificationStore(ProceedingJoinPoint joinPoint) throws Throwable {
        return loadTestStepProfiler.profileChecked(
                "notification.store",
                true,
                (LoadTestStepProfiler.ThrowingSupplier<Object>) joinPoint::proceed
        );
    }

    @Around("""
            execution(* com.effectivedisco.service.NotificationService.getAndMarkAllRead(..)) ||
            execution(* com.effectivedisco.service.NotificationService.getAndMarkAllReadPage(..))
            """)
    public Object profileNotificationReadAllPage(ProceedingJoinPoint joinPoint) throws Throwable {
        return loadTestStepProfiler.profileChecked(
                "notification.read-all.page",
                true,
                (LoadTestStepProfiler.ThrowingSupplier<Object>) joinPoint::proceed
        );
    }

    @Around("execution(* com.effectivedisco.service.NotificationService.markAllAsReadForLoadTest(..))")
    public Object profileNotificationReadAllSummary(ProceedingJoinPoint joinPoint) throws Throwable {
        return loadTestStepProfiler.profileChecked(
                "notification.read-all.summary",
                true,
                (LoadTestStepProfiler.ThrowingSupplier<Object>) joinPoint::proceed
        );
    }

    @Around("execution(* com.effectivedisco.service.NotificationService.markPageAsReadForLoadTest(..))")
    public Object profileNotificationReadPageSummary(ProceedingJoinPoint joinPoint) throws Throwable {
        return loadTestStepProfiler.profileChecked(
                "notification.read-page.summary",
                true,
                (LoadTestStepProfiler.ThrowingSupplier<Object>) joinPoint::proceed
        );
    }

    @Around("execution(* com.effectivedisco.service.PostService.createPost(..))")
    public Object profilePostCreate(ProceedingJoinPoint joinPoint) throws Throwable {
        return loadTestStepProfiler.profileChecked(
                "post.create",
                true,
                (LoadTestStepProfiler.ThrowingSupplier<Object>) joinPoint::proceed
        );
    }

    @Around("""
            execution(* com.effectivedisco.service.PostService.getPosts(int, int, String, String, String)) ||
            execution(* com.effectivedisco.service.PostService.getPosts(int, int, String, String, String, String))
            """)
    public Object profilePostList(ProceedingJoinPoint joinPoint) throws Throwable {
        return loadTestStepProfiler.profileChecked(
                "post.list",
                false,
                (LoadTestStepProfiler.ThrowingSupplier<Object>) joinPoint::proceed
        );
    }
}
