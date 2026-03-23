package com.effectivedisco.loadtest;

/**
 * 문제 해결:
 * top-level 메서드 단위 계측만으로는 "createPost 안의 어떤 단계가 pool을 잡아먹는가"를 구분하기 어렵다.
 * 서비스 메서드 안에서도 loadtest 전용 서브스텝을 같은 형식으로 기록할 수 있게 공용 profiler 인터페이스를 둔다.
 */
public interface LoadTestStepProfiler {

    <T> T profileChecked(String profileName,
                         boolean transactionalWindow,
                         ThrowingSupplier<T> supplier) throws Throwable;

    default void profileChecked(String profileName,
                                boolean transactionalWindow,
                                ThrowingRunnable runnable) throws Throwable {
        profileChecked(profileName, transactionalWindow, () -> {
            runnable.run();
            return null;
        });
    }

    default <T> T profile(String profileName,
                          boolean transactionalWindow,
                          ThrowingSupplier<T> supplier) {
        try {
            return profileChecked(profileName, transactionalWindow, supplier);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Unexpected checked exception during load-test profiling", throwable);
        }
    }

    default void profile(String profileName,
                         boolean transactionalWindow,
                         ThrowingRunnable runnable) {
        profile(profileName, transactionalWindow, () -> {
            runnable.run();
            return null;
        });
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
