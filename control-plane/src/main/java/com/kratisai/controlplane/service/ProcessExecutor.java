package com.kratisai.controlplane.service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ProcessExecutor {

    public record ProcessResult(int exitCode, byte[] output) {}

    public ProcessResult execute(List<String> command, File directory) throws IOException, InterruptedException {
        return execute(command, directory, null);
    }

    public ProcessResult execute(List<String> command, File directory, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(directory).redirectErrorStream(true);

        if (environment != null) {
            pb.environment().putAll(environment);
        }

        Process process = pb.start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();

        return new ProcessResult(exitCode, output);
    }
}
