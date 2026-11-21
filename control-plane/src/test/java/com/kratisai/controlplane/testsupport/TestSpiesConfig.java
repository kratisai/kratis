package com.kratisai.controlplane.testsupport;

import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiGenerationService;
import com.kratisai.controlplane.ingestion.teamwiki.TeamWikiWorker;
import com.kratisai.controlplane.ingestion.write.SemanticIndexingService;
import com.kratisai.controlplane.service.VirtualKeyService;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Wraps select service beans in delegating Mockito mocks so tests that need to stub or verify
 * them can {@code @Autowired} the shared bean instead of declaring {@code @MockitoBean} (which
 * forks a dedicated Spring {@code ApplicationContext} and stalls parallel worker threads).
 *
 * <p>Uses {@link AdditionalAnswers#delegatesTo(Object)} after AOP proxy creation so {@code @Async}
 * and {@code @Transactional} proxies remain intact while {@link ResetTestSpiesTestExecutionListener}
 * clears stubbing and invocation history between test methods.
 */
@TestConfiguration
public class TestSpiesConfig {

    private static final Set<Class<?>> SPIED_BEAN_TYPES = Set.of(
            TeamWikiWorker.class,
            TeamWikiGenerationService.class,
            SemanticIndexingService.class,
            VirtualKeyService.class);

    @Bean
    public static BeanPostProcessor integrationSpyBeanPostProcessor() {
        return new IntegrationSpyBeanPostProcessor();
    }

    static <T> T wrapAsDelegatingSpy(Class<T> targetClass, Object bean) {
        return Mockito.mock(targetClass, AdditionalAnswers.delegatesTo(bean));
    }

    private static final class IntegrationSpyBeanPostProcessor implements BeanPostProcessor, Ordered {

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        @Override
        public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            if (SPIED_BEAN_TYPES.contains(targetClass)
                    && !Mockito.mockingDetails(bean).isMock()) {
                return wrapAsDelegatingSpy(targetClass, bean);
            }
            return bean;
        }
    }
}
