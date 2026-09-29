package com.kratisai.controlplane.api.wsdto;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.wsprotocol.Direction;
import com.kratisai.controlplane.api.wsprotocol.MessageKind;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocolDefaultsTest {

    @Test
    void clientRpcPayload_defaultsAreRequestFromWeb() {
        ClientRpcPayload payload = new ClientRpcPayload.Ping();

        assertThat(payload.messageKind()).isEqualTo(MessageKind.REQUEST);
        assertThat(payload.direction()).isEqualTo(Direction.WEB_TO_CONTROL_PLANE);
        assertThat(payload.method()).isEqualTo(ClientRpcPayload.Ping.METHOD);
    }

    @Test
    void environmentRpcPayload_inboundRequestDefaults() {
        EnvironmentRpcPayload payload = new EnvironmentRpcPayload.Heartbeat();

        assertThat(payload.messageKind()).isEqualTo(MessageKind.REQUEST);
        assertThat(payload.direction()).isEqualTo(Direction.CONNECTOR_TO_CONTROL_PLANE);
        assertThat(payload.method()).isEqualTo(EnvironmentRpcPayload.Heartbeat.METHOD);
    }

    @Test
    void environmentRpcPayload_inboundNotificationDefaults() {
        EnvironmentRpcPayload payload = new EnvironmentRpcPayload.Output("line", OutputStream.STDOUT, "exec-1");

        assertThat(payload.messageKind()).isEqualTo(MessageKind.NOTIFICATION);
        assertThat(payload.direction()).isEqualTo(Direction.CONNECTOR_TO_CONTROL_PLANE);
        assertThat(payload.method()).isEqualTo(EnvironmentRpcPayload.Output.METHOD);
    }

    @Test
    void environmentRpcPayload_outboundRequestDefaults() {
        EnvironmentRpcPayload payload = new EnvironmentRpcPayload.Exec("ls", true, "exec-1");

        assertThat(payload.messageKind()).isEqualTo(MessageKind.REQUEST);
        assertThat(payload.direction()).isEqualTo(Direction.CONTROL_PLANE_TO_CONNECTOR);
        assertThat(payload.method()).isEqualTo(EnvironmentRpcPayload.Exec.METHOD);
    }

    @Test
    void environmentRpcPayload_outboundNotificationDefaults() {
        EnvironmentRpcPayload payload =
                new EnvironmentRpcPayload.Checkout("https://github.com/org/repo", "main", Map.of(), "exec-1");

        assertThat(payload.messageKind()).isEqualTo(MessageKind.NOTIFICATION);
        assertThat(payload.direction()).isEqualTo(Direction.CONTROL_PLANE_TO_CONNECTOR);
        assertThat(payload.method()).isEqualTo(EnvironmentRpcPayload.Checkout.METHOD);
    }

    @Test
    void environmentRpcPayload_gitDiffDefaults() {
        EnvironmentRpcPayload payloadSummary = new EnvironmentRpcPayload.GitDiffSummary("main", "exec-1");
        assertThat(payloadSummary.messageKind()).isEqualTo(MessageKind.REQUEST);
        assertThat(payloadSummary.direction()).isEqualTo(Direction.CONTROL_PLANE_TO_CONNECTOR);
        assertThat(payloadSummary.method()).isEqualTo(EnvironmentRpcPayload.GitDiffSummary.METHOD);

        EnvironmentRpcPayload payloadFile = new EnvironmentRpcPayload.GitFileDiff("src/A.java", "main", "exec-1");
        assertThat(payloadFile.messageKind()).isEqualTo(MessageKind.REQUEST);
        assertThat(payloadFile.direction()).isEqualTo(Direction.CONTROL_PLANE_TO_CONNECTOR);
        assertThat(payloadFile.method()).isEqualTo(EnvironmentRpcPayload.GitFileDiff.METHOD);

        EnvironmentRpcPayload payloadSlice = new EnvironmentRpcPayload.ReadFileSlice("src/A.java", 1, 10, "exec-1");
        assertThat(payloadSlice.messageKind()).isEqualTo(MessageKind.REQUEST);
        assertThat(payloadSlice.direction()).isEqualTo(Direction.CONTROL_PLANE_TO_CONNECTOR);
        assertThat(payloadSlice.method()).isEqualTo(EnvironmentRpcPayload.ReadFileSlice.METHOD);
    }
}
