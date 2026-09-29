package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.git.provider.GitCommandRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessGitCommandRunnerTest {

    @Mock
    private ProcessExecutor processExecutor;

    private ProcessGitCommandRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ProcessGitCommandRunner(processExecutor);
    }

    @Test
    void run_delegatesToProcessExecutorWithoutDirectory() throws Exception {
        when(processExecutor.execute(eq(List.of("git", "archive", "--remote=url", "main:README.md")), eq(null), any()))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "content".getBytes(StandardCharsets.UTF_8)));

        GitCommandRunner.Result result =
                runner.run(List.of("git", "archive", "--remote=url", "main:README.md"), Map.of("K", "V"));

        assertThat(result.exitCode()).isZero();
        assertThat(new String(result.output(), StandardCharsets.UTF_8)).isEqualTo("content");
        verify(processExecutor)
                .execute(
                        eq(List.of("git", "archive", "--remote=url", "main:README.md")),
                        eq(null),
                        eq(Map.of("K", "V")));
    }

    @Test
    void run_propagatesIOException() throws Exception {
        when(processExecutor.execute(any(), any(), any())).thenThrow(new IOException("boom"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.run(List.of("git"), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("boom");
    }
}
