package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.HarnessCatalogFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeminiAcpPatchTest {

    private static final String MARKER = "__KRATIS_ACP_TOOL_OUTPUT_CAP__";

    // Minimal stand-in for the ACP tool runner: it contains the anchor the patch rewrites and, when
    // executed after patching, calls runTool with oversized shell output and reports what happened.
    private static final String FIXTURE = """
            function toToolCallContent(toolResult) {
              return { type: 'content', content: { type: 'text', text: String(toolResult.returnDisplay ?? '') } }
            }

            const session = {
              context: { config: { getTruncateToolOutputThreshold: () => 1000 } },
              async runTool(fc, toolResult) {
                const content = toToolCallContent(toolResult);
                return { content, llmContent: toolResult.llmContent }
              },
            }

            const big = 'x'.repeat(5000)
            const result = await session.runTool({ name: 'run_shell_command' }, { llmContent: big, returnDisplay: 'ok' })
            console.log('capped=' + (result.llmContent.length < big.length))
            console.log('notice=' + result.llmContent.includes('truncated by Kratis'))
            console.log('filePointer=' + /tool-outputs/.test(result.llmContent))
            """;

    @BeforeAll
    static void loadHarnessCatalog() {
        HarnessCatalogFixture.load();
    }

    @Test
    void bundledScriptRewritesTheAcpRunnerAnchor() throws IOException {
        String script = patchScript();

        assertThat(script)
                .contains(MARKER)
                .contains("const content = toToolCallContent(toolResult);")
                .contains("getTruncateToolOutputThreshold");
    }

    @Test
    void script_capsShellOutputWhenPatchedIntoAcpRunner(@TempDir Path dir) throws Exception {
        assertNodeAvailable();
        Path patch = writePatch(dir, patchScript());
        Path bundle = Files.createDirectories(dir.resolve("bundle"));
        Path fixture = bundle.resolve("fixture.mjs");
        Files.writeString(fixture, FIXTURE);

        runNode(bundle, patch.toString(), bundle.toString());
        assertThat(Files.readString(fixture)).contains(MARKER);

        // Re-running must be idempotent: the runner is never patched twice.
        runNode(bundle, patch.toString(), bundle.toString());
        assertThat(Files.readString(fixture)).containsOnlyOnce(MARKER);

        String output = runNode(bundle, fixture.toString());
        assertThat(output).contains("capped=true").contains("notice=true").contains("filePointer=true");

        // The full output is retained on disk and pointed at from the truncated message.
        try (var files = Files.list(bundle.resolve(".kratis/tool-outputs"))) {
            List<Path> written = files.toList();
            assertThat(written).hasSize(1);
            assertThat(Files.readString(written.getFirst())).isEqualTo("x".repeat(5000));
        }
    }

    @Test
    void script_failsLoudlyWhenAnchorMissing(@TempDir Path dir) throws Exception {
        assertNodeAvailable();
        Path patch = writePatch(dir, patchScript());
        Path bundle = Files.createDirectories(dir.resolve("bundle"));
        Files.writeString(bundle.resolve("chunk.js"), "const x = 1;\n");

        Process process = new ProcessBuilder("node", patch.toString(), bundle.toString())
                .directory(bundle.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor())
                .as("the patch must fail loudly when the ACP anchor is gone:\n" + output)
                .isNotZero();
        assertThat(output).contains("anchor found");
    }

    private static HarnessResource geminiPatch() {
        return AgentHarness.valueOf("GEMINI").getResources().stream()
                .filter(resource -> resource.target().endsWith("kratis-gemini-acp-truncation.mjs"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("GEMINI must declare the ACP tool-output patch resource"));
    }

    private static String patchScript() throws IOException {
        return Files.readString(
                HarnessCatalogFixture.DIRECTORY.resolve(geminiPatch().source()), StandardCharsets.UTF_8);
    }

    private static Path writePatch(Path dir, String script) throws IOException {
        Path patch = Files.createDirectories(dir.resolve("patch")).resolve("patch.mjs");
        Files.writeString(patch, script);
        return patch;
    }

    private static String runNode(Path workingDir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("node");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor())
                .as("node %s failed:\n%s", String.join(" ", args), output)
                .isZero();
        return output;
    }

    private static void assertNodeAvailable() throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new AssertionError("Node.js is required to verify the gemini ACP patch script", e);
        }
        assertThat(process.waitFor())
                .as("node --version must succeed; install Node.js")
                .isZero();
    }
}
