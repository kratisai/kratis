package com.kratisai.controlplane.e2e;

import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for verifying files inside Docker containers using {@code docker exec}.
 */
public class DockerExecVerifier {

    private static final Logger logger = LoggerFactory.getLogger(DockerExecVerifier.class);

    private final ProcessExecutor processExecutor;
    private final String containerId;

    public DockerExecVerifier(ProcessExecutor processExecutor, String containerId) {
        this.processExecutor = processExecutor;
        this.containerId = containerId;
    }

    public void verifyFileExists(String filePath) {
        logger.info("[docker-verify] Checking file exists: {} in container {}", filePath, containerId);

        ProcessExecutor.ProcessResult result = executeInContainer("test", "-f", filePath);

        if (result.exitCode() != 0) {
            throw new AssertionError(String.format(
                    "File '%s' does not exist in container '%s'. "
                            + "The agent may not have created the file, or it was created at a different path.",
                    filePath, containerId));
        }

        logger.info("[docker-verify] File exists: {}", filePath);
    }

    public void verifyFileContains(String filePath, String expectedContent) {
        logger.info(
                "[docker-verify] Checking file content: {} in container {} for '{}'",
                filePath,
                containerId,
                expectedContent);

        // First verify the file exists
        verifyFileExists(filePath);

        // Then read and check content
        ProcessExecutor.ProcessResult result = executeInContainer("cat", filePath);

        if (result.exitCode() != 0) {
            throw new AssertionError(String.format(
                    "Failed to read file '%s' in container '%s'. Exit code: %d",
                    filePath, containerId, result.exitCode()));
        }

        String actualContent = new String(result.output());
        if (!actualContent.contains(expectedContent)) {
            throw new AssertionError(String.format("""
                            File '%s' in container '%s' does not contain expected content.
                            Expected to contain: '%s'
                            Actual content: '%s'""", filePath, containerId, expectedContent, actualContent));
        }

        logger.info("[docker-verify] File content verified: {}", filePath);
    }

    public void verifyFileContentEquals(String filePath, String expectedContent) {
        logger.info("[docker-verify] Checking exact file content: {} in container {}", filePath, containerId);

        // First verify the file exists
        verifyFileExists(filePath);

        // Then read and check content
        ProcessExecutor.ProcessResult result = executeInContainer("cat", filePath);

        if (result.exitCode() != 0) {
            throw new AssertionError(String.format(
                    "Failed to read file '%s' in container '%s'. Exit code: %d",
                    filePath, containerId, result.exitCode()));
        }

        String actualContent = new String(result.output());
        if (!actualContent.equals(expectedContent)) {
            throw new AssertionError(String.format("""
                            File '%s' in container '%s' does not have exact expected content.
                            Expected: '%s'
                            Actual: '%s'""", filePath, containerId, expectedContent, actualContent));
        }

        logger.info("[docker-verify] Exact file content verified: {}", filePath);
    }

    public void verifyFilePermissions(String filePath, String expectedPermissions) {
        logger.info(
                "[docker-verify] Checking file permissions: {} in container {} for '{}'",
                filePath,
                containerId,
                expectedPermissions);

        // First verify the file exists
        verifyFileExists(filePath);

        // Get file permissions using stat
        ProcessExecutor.ProcessResult result = executeInContainer("stat", "-c", "%a", filePath);

        if (result.exitCode() != 0) {
            throw new AssertionError(String.format(
                    "Failed to get permissions for file '%s' in container '%s'. Exit code: %d",
                    filePath, containerId, result.exitCode()));
        }

        String actualPermissions = new String(result.output()).trim();
        if (!actualPermissions.equals(expectedPermissions)) {
            throw new AssertionError(String.format("""
                            File '%s' in container '%s' does not have expected permissions.
                            Expected: '%s'
                            Actual: '%s'""", filePath, containerId, expectedPermissions, actualPermissions));
        }

        logger.info("[docker-verify] File permissions verified: {} = {}", filePath, expectedPermissions);
    }

    public void verifyFileIsExecutable(String filePath) {
        logger.info("[docker-verify] Checking file is executable: {} in container {}", filePath, containerId);

        ProcessExecutor.ProcessResult result = executeInContainer("test", "-x", filePath);

        if (result.exitCode() != 0) {
            throw new AssertionError(
                    String.format("File '%s' in container '%s' is not executable.", filePath, containerId));
        }

        logger.info("[docker-verify] File is executable: {}", filePath);
    }

    public void verifyDirectoryExists(String dirPath) {
        logger.info("[docker-verify] Checking directory exists: {} in container {}", dirPath, containerId);

        ProcessExecutor.ProcessResult result = executeInContainer("test", "-d", dirPath);

        if (result.exitCode() != 0) {
            throw new AssertionError(
                    String.format("Directory '%s' does not exist in container '%s'.", dirPath, containerId));
        }

        logger.info("[docker-verify] Directory exists: {}", dirPath);
    }

    public ProcessExecutor.ProcessResult executeInContainer(String... command) {
        List<String> dockerExecCommand = new java.util.ArrayList<>();
        dockerExecCommand.add("docker");
        dockerExecCommand.add("exec");
        dockerExecCommand.add(containerId);
        dockerExecCommand.addAll(List.of(command));

        try {
            return processExecutor.execute(dockerExecCommand, null, null);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(
                    String.format("Failed to execute command in container '%s': %s", containerId, e.getMessage()), e);
        }
    }

    public String getFileContent(String filePath) {
        ProcessExecutor.ProcessResult result = executeInContainer("cat", filePath);

        if (result.exitCode() != 0) {
            throw new AssertionError(String.format(
                    "Failed to read file '%s' in container '%s'. Exit code: %d",
                    filePath, containerId, result.exitCode()));
        }

        return new String(result.output());
    }

    public boolean fileExists(String filePath) {
        ProcessExecutor.ProcessResult result = executeInContainer("test", "-f", filePath);
        return result.exitCode() == 0;
    }

    public boolean directoryExists(String dirPath) {
        ProcessExecutor.ProcessResult result = executeInContainer("test", "-d", dirPath);
        return result.exitCode() == 0;
    }
}
