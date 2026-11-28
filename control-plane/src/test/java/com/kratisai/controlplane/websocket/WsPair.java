package com.kratisai.controlplane.websocket;

import com.kratisai.controlplane.testsupport.SandboxExecutionScenarioFactory.ExecutionScenario;
import com.kratisai.controlplane.testsupport.TestDataFactory.AuthContext;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * Connects a UI client and/or sidecar fixture. Client subscribe is processed asynchronously, so
 * the sidecar is connected only after that registry write has had time to land.
 */
public final class WsPair implements AutoCloseable {

    // Handshake + server-side subscription registration must survive heavily loaded CI forked
    // JVMs; 5s intermittently expired before the WS upgrade answered (CI TimeoutException at WsPair.connect).
    static final int CONNECT_TIMEOUT_SECONDS = 15;

    // The test server binds IPv4 (server.address=0.0.0.0); localhost can resolve to ::1, which is
    // refused on runners where the connector is not dual-stack.
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final ClientWebSocketFixture clientFixture;
    private final SidecarWebSocketFixture sidecarFixture;
    private final WebSocketSession clientSession;
    private final WebSocketSession sidecarSession;

    WsPair(
            ClientWebSocketFixture clientFixture,
            SidecarWebSocketFixture sidecarFixture,
            WebSocketSession clientSession,
            WebSocketSession sidecarSession) {
        this.clientFixture = clientFixture;
        this.sidecarFixture = sidecarFixture;
        this.clientSession = clientSession;
        this.sidecarSession = sidecarSession;
    }

    public static WsPair connect(
            int port,
            String accessToken,
            UUID teamId,
            String connectorToken,
            Consumer<ClientWebSocketFixture> configureClient,
            Consumer<SidecarWebSocketFixture> configureSidecar)
            throws Exception {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(connectorToken, "connectorToken");
        Objects.requireNonNull(configureClient, "configureClient");
        Objects.requireNonNull(configureSidecar, "configureSidecar");
        ClientWebSocketFixture clientFixture = new ClientWebSocketFixture(accessToken, teamId);
        configureClient.accept(clientFixture);
        SidecarWebSocketFixture sidecarFixture = new SidecarWebSocketFixture(connectorToken);
        configureSidecar.accept(sidecarFixture);
        StandardWebSocketClient wsClient = webSocketClient();
        WebSocketSession clientSession = null;
        WebSocketSession sidecarSession = null;
        try {
            clientSession =
                    wsClient.execute(clientFixture, clientUrl(port)).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitClientSubscribe(clientFixture);
            sidecarSession =
                    wsClient.execute(sidecarFixture, envUrl(port)).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new WsPair(clientFixture, sidecarFixture, clientSession, sidecarSession);
        } catch (Exception | Error failure) {
            closeAfterFailure(sidecarSession, failure);
            closeAfterFailure(clientSession, failure);
            throw failure;
        }
    }

    public static WsPair connect(
            int port,
            AuthContext auth,
            ExecutionScenario scenario,
            Consumer<ClientWebSocketFixture> configureClient,
            Consumer<SidecarWebSocketFixture> configureSidecar)
            throws Exception {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(scenario, "scenario");
        return connect(
                port,
                auth.accessToken(),
                auth.team().getId(),
                scenario.connectorToken(),
                configureClient,
                configureSidecar);
    }

    public static WsPair connectClient(
            int port, String accessToken, UUID teamId, Consumer<ClientWebSocketFixture> configureClient)
            throws Exception {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(configureClient, "configureClient");
        ClientWebSocketFixture clientFixture = new ClientWebSocketFixture(accessToken, teamId);
        configureClient.accept(clientFixture);
        WebSocketSession clientSession = null;
        try {
            clientSession = webSocketClient()
                    .execute(clientFixture, clientUrl(port))
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitClientSubscribe(clientFixture);
            return new WsPair(clientFixture, null, clientSession, null);
        } catch (Exception | Error failure) {
            closeAfterFailure(clientSession, failure);
            throw failure;
        }
    }

    public static WsPair connectClient(int port, AuthContext auth, Consumer<ClientWebSocketFixture> configureClient)
            throws Exception {
        Objects.requireNonNull(auth, "auth");
        return connectClient(port, auth.accessToken(), auth.team().getId(), configureClient);
    }

    public static WsPair connectSidecar(
            int port, String connectorToken, Consumer<SidecarWebSocketFixture> configureSidecar) throws Exception {
        Objects.requireNonNull(connectorToken, "connectorToken");
        Objects.requireNonNull(configureSidecar, "configureSidecar");
        SidecarWebSocketFixture sidecarFixture = new SidecarWebSocketFixture(connectorToken);
        configureSidecar.accept(sidecarFixture);
        WebSocketSession sidecarSession =
                webSocketClient().execute(sidecarFixture, envUrl(port)).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return new WsPair(null, sidecarFixture, null, sidecarSession);
    }

    public static WsPair connectSidecar(
            int port, ExecutionScenario scenario, Consumer<SidecarWebSocketFixture> configureSidecar) throws Exception {
        Objects.requireNonNull(scenario, "scenario");
        return connectSidecar(port, scenario.connectorToken(), configureSidecar);
    }

    static StandardWebSocketClient webSocketClient() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        // Tomcat's client defaults connect/write/read I/O to 5s; align it with the test budget so
        // loaded CI forks cannot fail the handshake before the outer future timeout.
        client.setUserProperties(Map.of(
                "org.apache.tomcat.websocket.IO_TIMEOUT_MS",
                String.valueOf(TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_SECONDS))));
        return client;
    }

    public static String clientUrl(int port) {
        return "ws://" + LOOPBACK_HOST + ":" + port + "/ws/client";
    }

    public static String envUrl(int port) {
        return "ws://" + LOOPBACK_HOST + ":" + port + "/ws/env";
    }

    static void awaitClientSubscribe(ClientWebSocketFixture clientFixture)
            throws InterruptedException, TimeoutException {
        if (clientFixture != null && !clientFixture.awaitSubscription(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new TimeoutException("Client WebSocket subscription was not acknowledged");
        }
    }

    private static void closeAfterFailure(WebSocketSession session, Throwable failure) {
        try {
            closeIfOpen(session);
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    public ClientWebSocketFixture client() {
        return clientFixture;
    }

    public SidecarWebSocketFixture sidecar() {
        return sidecarFixture;
    }

    public WebSocketSession clientSession() {
        return clientSession;
    }

    public WebSocketSession sidecarSession() {
        return sidecarSession;
    }

    @Override
    public void close() throws IOException {
        try {
            closeIfOpen(sidecarSession);
        } finally {
            closeIfOpen(clientSession);
        }
    }

    public static void closeIfOpen(WebSocketSession session) throws IOException {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }
}
