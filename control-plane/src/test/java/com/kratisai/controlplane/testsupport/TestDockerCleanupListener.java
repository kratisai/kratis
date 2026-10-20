package com.kratisai.controlplane.testsupport;

import java.io.IOException;
import java.util.List;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes Docker resources left behind by integration tests that exercise the docker host with
 * {@code kratis.instance.id=kratis-test}. The label scope means developer stacks are never
 * touched. Best-effort: without a docker CLI the e2e tests already fail on their own.
 */
public class TestDockerCleanupListener implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(TestDockerCleanupListener.class);
    private static final String TEST_INSTANCE_ID = "kratis-test";

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        clean();
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        clean();
    }

    private static void clean() {
        remove("container", List.of("docker", "ps", "-aq", "--filter", "label=kratis.instance.id=" + TEST_INSTANCE_ID));
        remove(
                "network",
                List.of("docker", "network", "ls", "-q", "--filter", "label=kratis.instance.id=" + TEST_INSTANCE_ID));
        remove(
                "image",
                List.of(
                        "docker",
                        "images",
                        "-q",
                        "--filter",
                        "reference=kratis-snapshot-*:latest",
                        "--filter",
                        "label=kratis.instance.id=" + TEST_INSTANCE_ID));
    }

    private static void remove(String kind, List<String> listCommand) {
        List<String> ids;
        try {
            ids = run(listCommand).lines().filter(line -> !line.isBlank()).toList();
        } catch (IOException | InterruptedException e) {
            log.debug("Skipping test docker cleanup ({}): {}", kind, e.getMessage());
            return;
        }
        for (String id : ids) {
            try {
                switch (kind) {
                    case "container" -> run(List.of("docker", "rm", "-f", "-v", id));
                    case "network" -> run(List.of("docker", "network", "rm", id));
                    default -> run(List.of("docker", "rmi", "-f", id));
                }
            } catch (IOException | InterruptedException e) {
                log.warn("Failed to remove leftover test {} {}: {}", kind, id, e.getMessage());
            }
        }
        if (!ids.isEmpty()) {
            log.info("Removed {} leftover test {}(s)", ids.size(), kind);
        }
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("exit " + process.exitValue() + ": " + String.join(" ", command));
        }
        return output;
    }
}
