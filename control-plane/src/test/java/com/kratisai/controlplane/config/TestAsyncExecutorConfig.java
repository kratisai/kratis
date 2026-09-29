package com.kratisai.controlplane.config;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces {@code dispatchExecutor} and {@code usageRefreshScheduler} with tracking doubles. A
 * {@link BeanDefinitionRegistryPostProcessor} is required because component-scanned production
 * configuration is processed after imported test configuration, so a same-named {@code @Bean} would
 * be overridden back. See {@link TestIngestionExecutorConfig} for the same pattern.
 */
@TestConfiguration
public class TestAsyncExecutorConfig {

    public static final String DISPATCH_EXECUTOR_BEAN_NAME = "dispatchExecutor";
    public static final String USAGE_REFRESH_SCHEDULER_BEAN_NAME = "usageRefreshScheduler";

    @Bean
    public static BeanDefinitionRegistryPostProcessor trackedAsyncBeanOverrideProcessor() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) {
                replace(
                        registry,
                        DISPATCH_EXECUTOR_BEAN_NAME,
                        BeanDefinitionBuilder.genericBeanDefinition(TrackingVirtualThreadExecutor.class)
                                .setDestroyMethodName("close"));
                replace(
                        registry,
                        USAGE_REFRESH_SCHEDULER_BEAN_NAME,
                        BeanDefinitionBuilder.genericBeanDefinition(TrackingTaskScheduler.class)
                                .addPropertyValue("poolSize", 1)
                                .addPropertyValue("threadNamePrefix", "test-usage-refresh-")
                                .addPropertyValue("removeOnCancelPolicy", true));
            }

            @Override
            public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) {}

            private void replace(
                    BeanDefinitionRegistry registry, String beanName, BeanDefinitionBuilder beanDefinition) {
                if (registry.containsBeanDefinition(beanName)) {
                    registry.removeBeanDefinition(beanName);
                }
                registry.registerBeanDefinition(beanName, beanDefinition.getBeanDefinition());
            }
        };
    }
}
