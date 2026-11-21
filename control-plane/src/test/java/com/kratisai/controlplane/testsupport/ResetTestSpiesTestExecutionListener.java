package com.kratisai.controlplane.testsupport;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Resets shared test spies/delegating mocks before and after every test method so stubbing in
 * one test class never leaks into subsequent classes sharing the same Spring context.
 */
public class ResetTestSpiesTestExecutionListener implements TestExecutionListener, Ordered {

    private static final List<String> RESETTABLE_BEAN_NAMES = List.of(
            "testLiteLLMClient",
            "teamWikiWorker",
            "teamWikiGenerationService",
            "semanticIndexingService",
            "virtualKeyService");

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public void beforeTestMethod(@NonNull TestContext testContext) {
        resetInstantiatedSpies(testContext);
    }

    @Override
    public void afterTestMethod(@NonNull TestContext testContext) {
        resetInstantiatedSpies(testContext);
    }

    private static void resetInstantiatedSpies(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        ApplicationContext context = testContext.getApplicationContext();
        if (!(context instanceof ConfigurableApplicationContext configurable)) {
            return;
        }
        var beanFactory = configurable.getBeanFactory();
        for (String beanName : RESETTABLE_BEAN_NAMES) {
            if (beanFactory.containsSingleton(beanName)) {
                Object bean = beanFactory.getSingleton(beanName);
                if (bean != null
                        && (Mockito.mockingDetails(bean).isMock()
                                || Mockito.mockingDetails(bean).isSpy())) {
                    Mockito.reset(bean);
                }
            }
        }
    }
}
