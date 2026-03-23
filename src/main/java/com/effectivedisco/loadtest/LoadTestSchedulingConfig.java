package com.effectivedisco.loadtest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")
public class LoadTestSchedulingConfig {
}
