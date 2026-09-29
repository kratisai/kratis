package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProtocolFixtureRoundTripTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    @Test
    void testValidEnvironmentFixturesRoundTrip() throws Exception {
        Path validDir = Paths.get("../protocol/environment/examples/valid");
        if (!Files.exists(validDir)) {
            validDir = Paths.get("protocol/environment/examples/valid");
        }
        if (!Files.exists(validDir)) {
            return; // Skip if protocol directory not relative to current cwd
        }

        File[] files = validDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        assertThat(files).isNotNull();

        for (File file : files) {
            String json = Files.readString(file.toPath());
            JsonRpcInboundRequest request = objectMapper.readValue(json, JsonRpcInboundRequest.class);
            assertThat(request).isNotNull();
            assertThat(request.jsonrpc()).isEqualTo("2.0");
            assertThat(request.method()).isNotNull();

            // Verify serialization round-trip
            String roundTrip = objectMapper.writeValueAsString(request);
            assertThat(roundTrip).contains(request.method());
        }
    }

    @Test
    void testInvalidEnvironmentRegisterRejectUnknownProperty() {
        String invalidJson = """
            {
                "token": "tok-123",
                "protocolVersion": 1,
                "unexpectedField": "boom"
            }
            """;

        assertThatThrownBy(() -> objectMapper.readValue(invalidJson, EnvironmentRpcPayload.Register.class))
                .isInstanceOf(Exception.class);
    }
}
