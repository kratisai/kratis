package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.service.JwtService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringIntegrationTest
class WebSocketLargeMessageTest {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketLargeMessageTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataFactory testDataFactory;

    @Autowired
    private JwtService jwtService;

    private String authToken;

    @BeforeEach
    void setUp() {
        TestDataFactory.TestContext testContext = testDataFactory.createUserAndTeam();
        authToken = jwtService.generateAccessToken(
                testContext.user().getId(), testContext.user().getEmail());
    }

    @Test
    void testWebSocketAccepts10KBMessage() throws Exception {
        testLargeMessage(10 * 1024, "10KB");
    }

    @Test
    void testWebSocketAccepts100KBMessage() throws Exception {
        testLargeMessage(100 * 1024, "100KB");
    }

    @Test
    void testWebSocketAccepts1MBMessage() throws Exception {
        testLargeMessage(1024 * 1024, "1MB");
    }

    private void testLargeMessage(int sizeBytes, String sizeLabel) throws Exception {
        logger.info("[Test] Testing WebSocket with {} message", sizeLabel);

        CountDownLatch closeLatch = new CountDownLatch(1);
        CountDownLatch messageLatch = new CountDownLatch(1);
        final Integer[] closeCode = {null};
        final String[] closeReason = {null};

        TextWebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                String authMessage = String.format(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"auth\",\"params\":{\"token\":\"%s\"},\"id\":1}", authToken);
                session.sendMessage(new TextMessage(authMessage));

                String largePayload = "x".repeat(sizeBytes);
                String message = String.format(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"test\",\"params\":{\"data\":\"%s\"},\"id\":2}",
                        largePayload);

                logger.info("[Test] Sending {} message ({} bytes)", sizeLabel, message.length());
                session.sendMessage(new TextMessage(message));
                logger.info("[Test] Message sent successfully");
                messageLatch.countDown();
            }

            @Override
            public void handleTransportError(@NonNull WebSocketSession session, Throwable exception) {
                logger.error("[Test] Transport error: {}", exception.getMessage());
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, CloseStatus status) {
                logger.info("[Test] Connection closed: code={}, reason={}", status.getCode(), status.getReason());
                closeCode[0] = status.getCode();
                closeReason[0] = status.getReason();
                closeLatch.countDown();
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(handler, "ws://localhost:" + port + "/ws/client").get(5, TimeUnit.SECONDS);

        assertThat(messageLatch.await(5, TimeUnit.SECONDS))
                .as("Message should be sent successfully")
                .isTrue();

        Thread.sleep(1000);

        if (session.isOpen()) {
            session.close();
        }

        assertThat(closeLatch.await(5, TimeUnit.SECONDS)).isTrue();

        logger.info("[Test] {} test completed - close code: {}, reason: {}", sizeLabel, closeCode[0], closeReason[0]);

        assertThat(closeCode[0])
                .as("WebSocket should not close with 1009 (message too big) for %s message", sizeLabel)
                .isNotEqualTo(1009);

        assertThat(closeCode[0])
                .as("WebSocket should close normally (1000) for %s message", sizeLabel)
                .isEqualTo(1000);
    }
}
