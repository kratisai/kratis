package com.kratisai.controlplane.config;

import com.kratisai.controlplane.FakeChatModelConfig;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Test-only replacement for the production {@code repoIngestTaskExecutor} bean defined in
 * {@code AsyncConfig}. It is a real {@link ThreadPoolTaskExecutor} (preserving genuine
 * {@code @Async} concurrency semantics so real threading bugs are still caught) that additionally
 * propagates {@link FakeChatModelConfig}'s "use real model" test flags from the submitting thread
 * onto the worker thread via a {@code TaskDecorator}.
 *
 * <p>Why this is needed: {@code @UseRealLlmClient}/{@code @UseRealEmbeddingClient} set
 * {@code ThreadLocal}s on the JUnit test thread. {@code IngestionWorker.runIngestion} executes on
 * {@code repoIngestTaskExecutor}'s worker threads, which never inherit those {@code ThreadLocal}s
 * by default -- so without this test decorator, any real-ingestion-pipeline test would silently
 * keep using the faked {@code EmbeddingModelFactory}/{@code ChatModelFactory} on the worker
 * thread, even with the annotation applied, and would never exercise the real LiteLLM call path
 * (this was confirmed empirically: without this override, an ingestion batch "succeeds" using the
 * fake embedding model, producing zero requests against the real LiteLLM/upstream provider).
 *
 * <p>Overriding the bean purely by {@code @Bean} name is not reliable here: Spring processes
 * component-scanned {@code @Configuration} classes (like production's {@code AsyncConfig}) after
 * explicitly {@code @Import}-ed test configuration classes, so a same-named {@code @Bean} method
 * here would itself get silently overridden back to the production definition. Instead, a
 * {@link BeanDefinitionRegistryPostProcessor} is used, which Spring guarantees runs only after
 * *all* bean definitions (component-scanned and imported) have been registered -- making the
 * override deterministic regardless of import/scan order.
 *
 * <p>This is opt-in per test: a test class must both {@code @Import} this config and enable
 * {@code spring.main.allow-bean-definition-overriding=true} (e.g. via
 * {@code @TestPropertySource}) to activate the override for its Spring context. No production
 * code (in particular {@code AsyncConfig}) is modified for this purpose.
 */
@TestConfiguration
public class TestIngestionExecutorConfig {

    private static final String BEAN_NAME = "repoIngestTaskExecutor";
    private static final String DECORATOR_BEAN_NAME = "fakeChatModelPropagatingTaskDecorator";

    @Bean
    public static BeanDefinitionRegistryPostProcessor repoIngestTaskExecutorOverrideProcessor() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) {
                if (registry.containsBeanDefinition(BEAN_NAME)) {
                    registry.removeBeanDefinition(BEAN_NAME);
                }
                if (!registry.containsBeanDefinition(DECORATOR_BEAN_NAME)) {
                    registry.registerBeanDefinition(
                            DECORATOR_BEAN_NAME,
                            BeanDefinitionBuilder.genericBeanDefinition(FakeChatModelPropagatingTaskDecorator.class)
                                    .getBeanDefinition());
                }
                registry.registerBeanDefinition(
                        BEAN_NAME,
                        BeanDefinitionBuilder.genericBeanDefinition(ThreadPoolTaskExecutor.class)
                                .addPropertyValue("corePoolSize", 1)
                                .addPropertyValue("maxPoolSize", 1)
                                .addPropertyValue("queueCapacity", Integer.MAX_VALUE)
                                .addPropertyValue("threadNamePrefix", "test-repo-ingest-")
                                .addPropertyValue("waitForTasksToCompleteOnShutdown", true)
                                .addPropertyValue("awaitTerminationSeconds", 60)
                                .addPropertyReference("taskDecorator", DECORATOR_BEAN_NAME)
                                .setInitMethodName("initialize")
                                .setDestroyMethodName("shutdown")
                                .getBeanDefinition());
            }

            @Override
            public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) {
                // No-op: only bean *definition* registration needs to happen after scanning.
            }
        };
    }

    /**
     * Propagates {@link FakeChatModelConfig}'s "use real model" test flags from the submitting
     * thread onto the worker thread. Extracted as a concrete class (rather than a lambda) so the
     * bean definition remains AOT-compatible: Spring AOT cannot generate code for instance
     * suppliers or anonymous lambda property values.
     */
    public static class FakeChatModelPropagatingTaskDecorator implements TaskDecorator {

        @Override
        public @NonNull Runnable decorate(@NonNull Runnable runnable) {
            boolean useRealModel = FakeChatModelConfig.isUseRealModel();
            boolean useRealEmbeddingModel = FakeChatModelConfig.isUseRealEmbeddingModel();
            return () -> {
                FakeChatModelConfig.setUseRealModel(useRealModel);
                FakeChatModelConfig.setUseRealEmbeddingModel(useRealEmbeddingModel);
                try {
                    runnable.run();
                } finally {
                    FakeChatModelConfig.clear();
                }
            };
        }
    }
}
