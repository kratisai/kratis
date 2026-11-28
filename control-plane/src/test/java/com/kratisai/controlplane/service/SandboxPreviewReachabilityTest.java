package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kratisai.controlplane.service.ProcessExecutor.ProcessResult;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxPreviewReachabilityTest {

    private static final UUID ENV_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ProcessExecutor processExecutor = mock(ProcessExecutor.class);

    private static ProcessResult output(String text) {
        return new ProcessResult(0, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void hostTopologyDialsRunnerAddressWithoutJoiningNetwork() throws Exception {
        SandboxPreviewReachability reachability = new SandboxPreviewReachability(processExecutor);
        reachability.runningInContainer = false;
        when(processExecutor.execute(anyList(), any(), any())).thenReturn(output("10.210.1.3\n"));

        String upstream = reachability.resolveUpstream(ENV_ID, 3000);

        assertThat(upstream).isEqualTo("http://10.210.1.3:3000");
        // Host routing already reaches the sandbox bridge, so no network join is attempted.
        verify(processExecutor, times(1)).execute(anyList(), any(), any());
    }

    @Test
    void containerTopologyJoinsNetworkOnceThenCachesTheJoin() throws Exception {
        SandboxPreviewReachability reachability = new SandboxPreviewReachability(processExecutor);
        reachability.runningInContainer = true;
        // Call 1: inspect (IP) then connect (exit 0). Call 2: inspect only (join cached).
        when(processExecutor.execute(anyList(), any(), any()))
                .thenReturn(output("10.210.1.3\n"))
                .thenReturn(output("connected"))
                .thenReturn(output("10.210.1.3\n"));

        assertThat(reachability.resolveUpstream(ENV_ID, 3000)).isEqualTo("http://10.210.1.3:3000");
        assertThat(reachability.resolveUpstream(ENV_ID, 3001)).isEqualTo("http://10.210.1.3:3001");

        // First call: inspect (runner IP) + hostname (self id) + connect. Second call: inspect only,
        // because the join is cached. The IP is re-inspected per call so a resume is honored.
        verify(processExecutor, times(4)).execute(anyList(), any(), any());
    }

    @Test
    void resolveUpstreamThrowsWhenRunnerAddressMissing() throws Exception {
        SandboxPreviewReachability reachability = new SandboxPreviewReachability(processExecutor);
        reachability.runningInContainer = false;
        when(processExecutor.execute(anyList(), any(), any())).thenReturn(output("\n"));

        assertThatThrownBy(() -> reachability.resolveUpstream(ENV_ID, 3000))
                .isInstanceOf(SandboxPreviewReachability.PreviewUnreachableException.class)
                .hasMessageContaining("no address");
    }

    @Test
    void resolveUpstreamThrowsWhenInspectFails() throws Exception {
        SandboxPreviewReachability reachability = new SandboxPreviewReachability(processExecutor);
        reachability.runningInContainer = false;
        when(processExecutor.execute(anyList(), any(), any())).thenReturn(new ProcessResult(1, new byte[0]));

        assertThatThrownBy(() -> reachability.resolveUpstream(ENV_ID, 3000))
                .isInstanceOf(SandboxPreviewReachability.PreviewUnreachableException.class);
    }

    @Test
    void resolveUpstreamThrowsWhenNetworkJoinFails() throws Exception {
        SandboxPreviewReachability reachability = new SandboxPreviewReachability(processExecutor);
        reachability.runningInContainer = true;
        when(processExecutor.execute(anyList(), any(), any()))
                .thenReturn(output("10.210.1.3\n")) // inspect
                .thenReturn(new ProcessResult(1, "in use".getBytes(StandardCharsets.UTF_8))); // connect fails

        assertThatThrownBy(() -> reachability.resolveUpstream(ENV_ID, 3000))
                .isInstanceOf(SandboxPreviewReachability.PreviewUnreachableException.class)
                .hasMessageContaining("could not join");
    }
}
