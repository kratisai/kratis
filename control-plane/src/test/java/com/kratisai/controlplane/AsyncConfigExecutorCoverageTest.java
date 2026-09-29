package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.config.AsyncConfig;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;

/**
 * Every executor or scheduler bean defined in {@link AsyncConfig} must be drained by {@link
 * DrainExecutorsTestExecutionListener}. The ArchUnit rule {@code EXECUTOR_CREATION_ONLY_IN_ASYNC_CONFIG}
 * forces new executors into AsyncConfig, so this keeps creation and draining in lock-step.
 */
class AsyncConfigExecutorCoverageTest {

    @Test
    void everyAsyncConfigExecutorOrSchedulerBeanIsDrained() {
        Set<String> asyncBeans = Arrays.stream(AsyncConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> isExecutorOrScheduler(method.getReturnType()))
                .map(AsyncConfigExecutorCoverageTest::beanName)
                .collect(Collectors.toSet());

        assertThat(asyncBeans).isNotEmpty();
        assertThat(DrainExecutorsTestExecutionListener.trackedBeanNames())
                .containsExactlyInAnyOrderElementsOf(asyncBeans);
    }

    private static boolean isExecutorOrScheduler(Class<?> type) {
        return Executor.class.isAssignableFrom(type)
                || ExecutorService.class.isAssignableFrom(type)
                || ScheduledExecutorService.class.isAssignableFrom(type)
                || TaskExecutor.class.isAssignableFrom(type)
                || TaskScheduler.class.isAssignableFrom(type);
    }

    private static String beanName(Method method) {
        Bean bean = method.getAnnotation(Bean.class);
        if (bean.name().length > 0) {
            return bean.name()[0];
        }
        if (bean.value().length > 0) {
            return bean.value()[0];
        }
        return method.getName();
    }
}
