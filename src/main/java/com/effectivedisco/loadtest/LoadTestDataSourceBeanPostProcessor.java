package com.effectivedisco.loadtest;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 문제 해결:
 * 서비스 코드마다 JDBC 타이머를 심으면 관측 지점이 흩어지고 운영 코드가 더러워진다.
 * loadtest 프로필에서만 DataSource를 한 번 감싸 statement 실행 시간을 공통 수집하면
 * 기존 비즈니스 로직을 건드리지 않고 SQL count/time을 계측할 수 있다.
 */
@Component
@ConditionalOnProperty(name = "app.load-test.enabled", havingValue = "true")
public class LoadTestDataSourceBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<LoadTestSqlProfiler> sqlProfilerProvider;

    public LoadTestDataSourceBeanPostProcessor(ObjectProvider<LoadTestSqlProfiler> sqlProfilerProvider) {
        this.sqlProfilerProvider = sqlProfilerProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource && !(bean instanceof LoadTestInstrumentedDataSource)) {
            // 문제 해결:
            // BeanPostProcessor 생성 시 profiler를 즉시 끌어오면 "not eligible for all BeanPostProcessors"
            // 경고가 남는다. 실제로 DataSource를 감싸는 순간에만 lazy lookup 하면 같은 기능을 유지하면서
            // loadtest 프로필 부팅 로그를 깨끗하게 유지할 수 있다.
            return new LoadTestInstrumentedDataSource(dataSource, sqlProfilerProvider.getObject());
        }
        return bean;
    }
}
