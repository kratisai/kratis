package com.kratisai.controlplane.git.provider;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Messy hack to avoid service packages depending on git packages depending on service packages.
 */
public interface GitCommandRunner {

    record Result(int exitCode, byte[] output) {}

    Result run(List<String> command, Map<String, String> environment) throws IOException, InterruptedException;
}
