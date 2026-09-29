package com.kratisai.controlplane.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DockerExecVerifier}.
 */
class DockerExecVerifierTest {

    private ProcessExecutor processExecutor;
    private DockerExecVerifier verifier;
    private static final String CONTAINER_ID = "test-container-123";

    @BeforeEach
    void setUp() {
        processExecutor = mock(ProcessExecutor.class);
        verifier = new DockerExecVerifier(processExecutor, CONTAINER_ID);
    }

    @Test
    void verifyFileExists_whenFileExists_shouldNotThrow() throws Exception {
        // Given: file exists (test -f returns 0)
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));

        // When/Then: no exception thrown
        verifier.verifyFileExists("/tmp/test.sh");

        // Verify docker exec was called with correct arguments
        verify(processExecutor)
                .execute(eq(List.of("docker", "exec", CONTAINER_ID, "test", "-f", "/tmp/test.sh")), eq(null), eq(null));
    }

    @Test
    void verifyFileExists_whenFileDoesNotExist_shouldThrowAssertionError() throws Exception {
        // Given: file does not exist (test -f returns 1)
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(1, new byte[0]));

        // When/Then: assertion error thrown
        assertThatThrownBy(() -> verifier.verifyFileExists("/tmp/nonexistent.sh"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not exist")
                .hasMessageContaining("/tmp/nonexistent.sh")
                .hasMessageContaining(CONTAINER_ID);
    }

    @Test
    void verifyFileContains_whenContentMatches_shouldNotThrow() throws Exception {
        // Given: file exists and contains expected content
        String expectedContent = "Hello World";
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "test", "-f", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "cat", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(
                        0, ("#!/bin/bash\necho \"" + expectedContent + "\"\n").getBytes()));

        // When/Then: no exception thrown
        verifier.verifyFileContains("/tmp/test.sh", expectedContent);
    }

    @Test
    void verifyFileContains_whenContentDoesNotMatch_shouldThrowAssertionError() throws Exception {
        // Given: file exists but does not contain expected content
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "test", "-f", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "cat", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "different content".getBytes()));

        // When/Then: assertion error thrown
        assertThatThrownBy(() -> verifier.verifyFileContains("/tmp/test.sh", "expected content"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not contain expected content")
                .hasMessageContaining("expected content")
                .hasMessageContaining("different content");
    }

    @Test
    void verifyFileContains_whenFileDoesNotExist_shouldThrowAssertionError() throws Exception {
        // Given: file does not exist
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(1, new byte[0]));

        // When/Then: assertion error thrown for file not existing
        assertThatThrownBy(() -> verifier.verifyFileContains("/tmp/nonexistent.sh", "content"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void verifyFileContentEquals_whenContentMatchesExactly_shouldNotThrow() throws Exception {
        // Given: file content matches exactly
        String exactContent = "#!/bin/bash\necho \"Hello\"\n";
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "test", "-f", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "cat", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, exactContent.getBytes()));

        // When/Then: no exception thrown
        verifier.verifyFileContentEquals("/tmp/test.sh", exactContent);
    }

    @Test
    void verifyFileContentEquals_whenContentDoesNotMatchExactly_shouldThrowAssertionError() throws Exception {
        // Given: file content does not match exactly
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "test", "-f", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "cat", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "actual content".getBytes()));

        // When/Then: assertion error thrown
        assertThatThrownBy(() -> verifier.verifyFileContentEquals("/tmp/test.sh", "expected content"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not have exact expected content");
    }

    @Test
    void verifyFilePermissions_whenPermissionsMatch_shouldNotThrow() throws Exception {
        // Given: file has expected permissions
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "test", "-f", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "stat", "-c", "%a", "/tmp/test.sh")),
                        eq(null),
                        eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "755\n".getBytes()));

        // When/Then: no exception thrown
        verifier.verifyFilePermissions("/tmp/test.sh", "755");
    }

    @Test
    void verifyFilePermissions_whenPermissionsDoNotMatch_shouldThrowAssertionError() throws Exception {
        // Given: file has different permissions
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "test", "-f", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "stat", "-c", "%a", "/tmp/test.sh")),
                        eq(null),
                        eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, "644\n".getBytes()));

        // When/Then: assertion error thrown
        assertThatThrownBy(() -> verifier.verifyFilePermissions("/tmp/test.sh", "755"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not have expected permissions")
                .hasMessageContaining("755")
                .hasMessageContaining("644");
    }

    @Test
    void verifyFileIsExecutable_whenFileIsExecutable_shouldNotThrow() throws Exception {
        // Given: file is executable (test -x returns 0)
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));

        // When/Then: no exception thrown
        verifier.verifyFileIsExecutable("/tmp/test.sh");

        // Verify docker exec was called with test -x
        verify(processExecutor)
                .execute(eq(List.of("docker", "exec", CONTAINER_ID, "test", "-x", "/tmp/test.sh")), eq(null), eq(null));
    }

    @Test
    void verifyFileIsExecutable_whenFileIsNotExecutable_shouldThrowAssertionError() throws Exception {
        // Given: file is not executable (test -x returns 1)
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(1, new byte[0]));

        // When/Then: assertion error thrown
        assertThatThrownBy(() -> verifier.verifyFileIsExecutable("/tmp/test.sh"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("is not executable");
    }

    @Test
    void verifyDirectoryExists_whenDirectoryExists_shouldNotThrow() throws Exception {
        // Given: directory exists (test -d returns 0)
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));

        // When/Then: no exception thrown
        verifier.verifyDirectoryExists("/tmp/testdir");

        // Verify docker exec was called with test -d
        verify(processExecutor)
                .execute(eq(List.of("docker", "exec", CONTAINER_ID, "test", "-d", "/tmp/testdir")), eq(null), eq(null));
    }

    @Test
    void verifyDirectoryExists_whenDirectoryDoesNotExist_shouldThrowAssertionError() throws Exception {
        // Given: directory does not exist (test -d returns 1)
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(1, new byte[0]));

        // When/Then: assertion error thrown
        assertThatThrownBy(() -> verifier.verifyDirectoryExists("/tmp/nonexistent"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void getFileContent_whenFileExists_shouldReturnContent() throws Exception {
        // Given: file exists with content
        String expectedContent = "file content here";
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "cat", "/tmp/test.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, expectedContent.getBytes()));

        // When
        String actualContent = verifier.getFileContent("/tmp/test.sh");

        // Then
        assertThat(actualContent).isEqualTo(expectedContent);
    }

    @Test
    void getFileContent_whenFileDoesNotExist_shouldThrowAssertionError() throws Exception {
        // Given: file does not exist (cat returns non-zero)
        when(processExecutor.execute(
                        eq(List.of("docker", "exec", CONTAINER_ID, "cat", "/tmp/nonexistent.sh")), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(1, new byte[0]));

        // When/Then: assertion error thrown
        assertThatThrownBy(() -> verifier.getFileContent("/tmp/nonexistent.sh"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Failed to read file");
    }

    @Test
    void fileExists_whenFileExists_shouldReturnTrue() throws Exception {
        // Given: file exists
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));

        // When
        boolean exists = verifier.fileExists("/tmp/test.sh");

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    void fileExists_whenFileDoesNotExist_shouldReturnFalse() throws Exception {
        // Given: file does not exist
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(1, new byte[0]));

        // When
        boolean exists = verifier.fileExists("/tmp/nonexistent.sh");

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    void directoryExists_whenDirectoryExists_shouldReturnTrue() throws Exception {
        // Given: directory exists
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(0, new byte[0]));

        // When
        boolean exists = verifier.directoryExists("/tmp/testdir");

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    void directoryExists_whenDirectoryDoesNotExist_shouldReturnFalse() throws Exception {
        // Given: directory does not exist
        when(processExecutor.execute(anyList(), eq(null), eq(null)))
                .thenReturn(new ProcessExecutor.ProcessResult(1, new byte[0]));

        // When
        boolean exists = verifier.directoryExists("/tmp/nonexistent");

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    void executeInContainer_whenIOExceptionThrown_shouldWrapInRuntimeException() throws Exception {
        // Given: process executor throws IOException
        when(processExecutor.execute(anyList(), eq(null), eq(null))).thenThrow(new IOException("Docker failed"));

        // When/Then: RuntimeException thrown
        assertThatThrownBy(() -> verifier.executeInContainer("test", "-f", "/tmp/test.sh"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to execute command in container")
                .hasMessageContaining(CONTAINER_ID)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void executeInContainer_whenInterruptedExceptionThrown_shouldWrapInRuntimeException() throws Exception {
        // Given: process executor throws InterruptedException
        when(processExecutor.execute(anyList(), eq(null), eq(null))).thenThrow(new InterruptedException("Interrupted"));

        // When/Then: RuntimeException thrown
        assertThatThrownBy(() -> verifier.executeInContainer("test", "-f", "/tmp/test.sh"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to execute command in container")
                .hasCauseInstanceOf(InterruptedException.class);
    }
}
