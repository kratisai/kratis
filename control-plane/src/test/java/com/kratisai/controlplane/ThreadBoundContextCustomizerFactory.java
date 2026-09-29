package com.kratisai.controlplane;

import java.util.List;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

/**
 * Registers {@link ThreadBoundContextCustomizer} for classes annotated with {@code @SpringIntegrationTest}.
 *
 * <p>This factory partitions integration tests into a fixed pool of isolated Spring contexts,
 * assigning slots based on a stable hash of the test class name. This guarantees deterministic
 * context assignment regardless of which JUnit worker thread is executing.
 *
 * <p>Note: Discovered via {@code META-INF/spring.factories}.
 */
public class ThreadBoundContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {

        // Use direct isAnnotationPresent — avoids deep @Import meta-annotation introspection
        // which can throw TypeNotPresentException for test-only classes (e.g. TestAgentConfig).
        if (testClass.isAnnotationPresent(SpringIntegrationTest.class)) {
            return ThreadBoundContextCustomizer.forClass(testClass);
        }

        // Leave other test configurations (e.g. LiteLLMProvisioningIntegrationTest) unaffected.
        return null;
    }
}
