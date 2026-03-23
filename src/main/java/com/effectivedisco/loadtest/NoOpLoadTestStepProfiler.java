package com.effectivedisco.loadtest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpLoadTestStepProfiler implements LoadTestStepProfiler {

    @Override
    public <T> T profileChecked(String profileName,
                                boolean transactionalWindow,
                                ThrowingSupplier<T> supplier) throws Throwable {
        return supplier.get();
    }
}
