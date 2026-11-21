package com.kratisai.controlplane.testsupport;

import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Provides a Mockito spy around the real {@link ProcessExecutor} bean in integration tests.
 *
 * <p>Tests that want real process execution (e.g. {@code SuspendResumeConnectorIntegrationTest},
 * {@code RealE2EAgentTest}) use it without stubbing, while tests that stub commands use
 * {@code doReturn(...).when(processExecutor).execute(...)} and call {@code Mockito.reset(processExecutor)}
 * in {@code @BeforeEach}/{@code @AfterEach} to restore real delegation.
 *
 * <p>Registered as {@code @Primary} in {@link SpringIntegrationTest}, so Spring injects the spy
 * everywhere without creating per-class {@code @MockitoBean} contexts.
 */
@TestConfiguration
public class ProcessExecutorTestConfig {

    @Bean
    @Primary
    public ProcessExecutor processExecutorSpy() {
        return Mockito.spy(new TimedProcessExecutor());
    }

    private static class TimedProcessExecutor extends ProcessExecutor {

        @Override
        public ProcessResult execute(List<String> command, File directory, Map<String, String> environment)
                throws IOException, InterruptedException {
            long start = System.nanoTime();
            int exitCode = -1;
            try {
                ProcessResult result = super.execute(command, directory, environment);
                exitCode = result.exitCode();
                return result;
            } finally {
                TestTimingRegistry.instance().recordProcessExec(command, System.nanoTime() - start, exitCode);
            }
        }
    }
}
