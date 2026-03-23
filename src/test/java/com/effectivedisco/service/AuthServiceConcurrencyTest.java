package com.effectivedisco.service;

import com.effectivedisco.dto.request.SignupRequest;
import com.effectivedisco.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AuthServiceConcurrencyTest {

    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void signup_concurrentDuplicateRequests_createSingleAccount() throws Exception {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        runConcurrently(8, () -> {
            try {
                authService.signup(makeSignupRequest("race-user", "race@test.com"));
                successCount.incrementAndGet();
            } catch (IllegalArgumentException e) {
                failureCount.incrementAndGet();
            }
        });

        assertThat(userRepository.findByUsername("race-user"))
                .as("문제 해결 검증: 같은 회원가입 요청이 동시에 들어와도 계정 row는 1개만 생성되어야 한다")
                .isPresent();
        assertThat(userRepository.count())
                .as("문제 해결 검증: username/email 유니크 제약 경합에서도 중복 계정이 남으면 안 된다")
                .isEqualTo(1);
        assertThat(successCount.get())
                .as("문제 해결 검증: 경쟁 상황에서도 적어도 하나의 요청은 정상 가입에 성공해야 한다")
                .isEqualTo(1);
        assertThat(failureCount.get())
                .as("문제 해결 검증: 나머지 중복 가입 요청은 예외로 흡수되어야 한다")
                .isEqualTo(7);
    }

    private SignupRequest makeSignupRequest(String username, String email) {
        SignupRequest request = new SignupRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPassword("pass123");
        return request;
    }

    private void runConcurrently(int workers, ThrowingRunnable action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                action.run();
                return null;
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        for (Future<?> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw (e.getCause() instanceof Exception ex) ? ex : e;
            }
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
