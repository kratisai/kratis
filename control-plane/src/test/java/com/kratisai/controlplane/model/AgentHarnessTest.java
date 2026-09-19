package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentHarnessTest {

    private static final String OPENCODE_BASH_TIMEOUT_ENV = "OPENCODE_EXPERIMENTAL_BASH_DEFAULT_TIMEOUT_MS";

    @Test
    void openCodeSetupCommands_raiseBashToolDefaultTimeoutAboveBuiltInDefault() {
        List<String> commands = AgentHarness.OPENCODE.getSetupCommands();

        String timeoutCommand = commands.stream()
                .filter(command -> command.contains(OPENCODE_BASH_TIMEOUT_ENV))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("OPENCODE setup commands must configure " + OPENCODE_BASH_TIMEOUT_ENV));

        long timeoutMs = Long.parseLong(
                timeoutCommand.substring(timeoutCommand.indexOf('=') + 1).trim());

        // OpenCode's built-in shell tool default is 120000 ms (2 minutes), which is too short
        // for full build and test commands.
        assertThat(timeoutMs).isGreaterThan(120_000L);
    }
}
