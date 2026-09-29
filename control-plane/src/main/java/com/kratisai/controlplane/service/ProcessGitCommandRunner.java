package com.kratisai.controlplane.service;

import com.kratisai.controlplane.git.provider.GitCommandRunner;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Messy hack to avoid service packages depending on git packages depending on service packages.
 */
@Component
public class ProcessGitCommandRunner implements GitCommandRunner {

    private final ProcessExecutor processExecutor;

    public ProcessGitCommandRunner(ProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
    }

    @Override
    public Result run(List<String> command, Map<String, String> environment) throws IOException, InterruptedException {
        ProcessExecutor.ProcessResult result = processExecutor.execute(command, null, environment);
        return new Result(result.exitCode(), result.output());
    }
}
