package com.kratisai.controlplane.testsupport;

import com.kratisai.controlplane.service.ProcessExecutor;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class ProcessExecutorTestConfig {

    @Bean
    @Primary
    public ProcessExecutor processExecutorSpy(ProcessExecutor realProcessExecutor) {
        return Mockito.spy(realProcessExecutor);
    }
}
