package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.SandboxExecutionScenarioFactory.ExecutionScenario;
import com.kratisai.controlplane.TestDataFactory.AuthContext;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.model.ModelProvider;
import com.kratisai.controlplane.model.ProviderType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.websocket.WsPair;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings("NP_NULL_PARAM_DEREF_NONVIRTUAL")
class ExecutionScenarioSupportTest {

    @Test
    void authContextRejectsNullFields() {
        Team team = new Team("t", "d");
        User user = new User();
        ModelProvider provider = new ModelProvider("p", ProviderType.OPENAI, "k", null);
        ExecutionEnvironment sandbox = new ExecutionEnvironment();
        assertThatThrownBy(() -> new AuthContext(null, user, "tok", provider, sandbox))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("team");
        assertThatThrownBy(() -> new AuthContext(team, null, "tok", provider, sandbox))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("user");
        assertThatThrownBy(() -> new AuthContext(team, user, null, provider, sandbox))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("accessToken");
        assertThatThrownBy(() -> new AuthContext(team, user, "tok", null, sandbox))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("provider");
        assertThatThrownBy(() -> new AuthContext(team, user, "tok", provider, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("defaultSandbox");
    }

    @Test
    void executionScenarioAllowsNullRepository() {
        UUID id = UUID.randomUUID();
        ExecutionScenario scenario = new ExecutionScenario(id, "tok", id, null, "doc");
        assertThat(scenario.repository()).isNull();
        assertThat(scenario.canvasDocumentId()).isEqualTo("doc");
    }

    @Test
    void executionScenarioRejectsNullRequiredFields() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new ExecutionScenario(null, "tok", id, null, "doc"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("envId");
        assertThatThrownBy(() -> new ExecutionScenario(id, null, id, null, "doc"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("connectorToken");
        assertThatThrownBy(() -> new ExecutionScenario(id, "tok", null, null, "doc"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("executionId");
        assertThatThrownBy(() -> new ExecutionScenario(id, "tok", id, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("canvasDocumentId");
    }

    @Test
    void wsPairBuildsLocalhostUrls() {
        assertThat(WsPair.clientUrl(1234)).isEqualTo("ws://localhost:1234/ws/client");
        assertThat(WsPair.envUrl(8080)).isEqualTo("ws://localhost:8080/ws/env");
    }

    @Test
    void wsPairCloseIfOpenAcceptsNull() throws Exception {
        WsPair.closeIfOpen(null);
    }

    @Test
    @SuppressWarnings("resource")
    void wsPairConnectOverloadsRejectNullContext() {
        UUID id = UUID.randomUUID();
        ExecutionScenario scenario = new ExecutionScenario(id, "tok", id, null, "doc");
        assertThatThrownBy(() -> WsPair.connect(1, null, scenario, c -> {}, s -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("auth");
        assertThatThrownBy(() -> WsPair.connectClient(1, null, c -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("auth");
        assertThatThrownBy(() -> WsPair.connectSidecar(1, (ExecutionScenario) null, s -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scenario");
    }

    @Test
    @SuppressWarnings("resource")
    void wsPairConnectOverloadsRejectNullScenario() {
        Team team = new Team("t", "d");
        User user = new User();
        ModelProvider provider = new ModelProvider("p", ProviderType.OPENAI, "k", null);
        ExecutionEnvironment sandbox = new ExecutionEnvironment();
        AuthContext auth = new AuthContext(team, user, "tok", provider, sandbox);
        assertThatThrownBy(() -> WsPair.connect(1, auth, null, c -> {}, s -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scenario");
    }
}
