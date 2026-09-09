package com.kratisai.controlplane.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "repoIngestTaskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(@Value("${kratis.ingestion.max-concurrent:2}") int maxConcurrent) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrent);
        executor.setMaxPoolSize(maxConcurrent);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("repo-ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean(name = "agentTaskExecutor")
    public ThreadPoolTaskExecutor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("agent-loop-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Unbounded queue: inbound sidecar messages must never be dropped.
     */
    @Bean(name = "envMessageExecutor")
    public ThreadPoolTaskExecutor envMessageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(200);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("env-rpc-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Unbounded queue: client broadcasts must never be dropped.
     */
    @Bean(name = "clientBroadcastExecutor")
    public ThreadPoolTaskExecutor clientBroadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("client-broadcast-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
