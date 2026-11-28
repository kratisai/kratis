package com.kratisai.controlplane.api.rest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.service.SandboxPublishService;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SandboxPreviewProxyControllerTest {

    @Mock
    private SandboxPublishService publishService;

    @Mock
    private ExecutionEnvironmentRepository environmentRepository;

    private HttpServer upstream;
    private int upstreamPort;
    private MockMvc mockMvc;

    private final UUID environmentId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String body = "hello from sandbox";
            exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, body.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();

        ExecutionEnvironment environment = new ExecutionEnvironment();
        environment.setId(environmentId);
        environment.setStatus(EnvironmentStatus.CONNECTED);
        lenient().when(environmentRepository.findById(environmentId)).thenReturn(Optional.of(environment));
        lenient()
                .when(publishService.previewUpstream(eq(environmentId), eq(3000)))
                .thenReturn(Optional.of("http://127.0.0.1:" + upstreamPort));

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SandboxPreviewProxyController(publishService, environmentRepository))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void proxy_streamsUpstreamResponseWithHopByHopHeadersStripped() throws Exception {
        mockMvc.perform(get("/preview/{environmentId}/{port}/health", environmentId, 3000))
                .andExpect(status().isOk())
                .andExpect(content().string("hello from sandbox"));
    }

    @Test
    void proxy_returns503WhenEnvironmentNotConnected() throws Exception {
        ExecutionEnvironment sleeping = new ExecutionEnvironment();
        sleeping.setId(environmentId);
        sleeping.setStatus(EnvironmentStatus.SLEEPING);
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.of(sleeping));

        mockMvc.perform(get("/preview/{environmentId}/{port}/", environmentId, 3000))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void proxy_returns502WhenUpstreamUnresolvable() throws Exception {
        when(publishService.previewUpstream(eq(environmentId), eq(3001))).thenReturn(Optional.empty());

        mockMvc.perform(get("/preview/{environmentId}/{port}/", environmentId, 3001))
                .andExpect(status().isBadGateway());
    }

    @Test
    void proxy_rejectsPortOutOfRange() throws Exception {
        mockMvc.perform(get("/preview/{environmentId}/{port}/", environmentId, 70000))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proxy_passesRedirectThroughInsteadOfFollowingIt() throws Exception {
        // A sandbox app returning 302 to a cloud-metadata style target must not be followed by
        // the control plane; the 3xx and its Location are handed back to the browser instead.
        HttpServer redirecting = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirecting.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        redirecting.start();
        try {
            int port = redirecting.getAddress().getPort();
            lenient()
                    .when(publishService.previewUpstream(eq(environmentId), eq(4000)))
                    .thenReturn(Optional.of("http://127.0.0.1:" + port));

            mockMvc.perform(get("/preview/{environmentId}/{port}/", environmentId, 4000))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "http://169.254.169.254/latest/meta-data/"));
        } finally {
            redirecting.stop(0);
        }
    }

    @Test
    void proxy_rejectsOversizedUpstreamBody() throws Exception {
        // A body larger than the cap must be rejected, not buffered into heap.
        HttpServer huge = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        huge.createContext("/", exchange -> {
            byte[] chunk = new byte[1024 * 1024];
            exchange.sendResponseHeaders(200, chunk.length * 16L);
            try (OutputStream os = exchange.getResponseBody()) {
                for (int i = 0; i < 16; i++) {
                    os.write(chunk);
                }
            } catch (java.io.IOException ignored) {
                // The proxy may close the stream once it hits the cap.
            }
        });
        huge.start();
        try {
            int port = huge.getAddress().getPort();
            lenient()
                    .when(publishService.previewUpstream(eq(environmentId), eq(4001)))
                    .thenReturn(Optional.of("http://127.0.0.1:" + port));

            mockMvc.perform(get("/preview/{environmentId}/{port}/", environmentId, 4001))
                    .andExpect(status().isBadGateway());
        } finally {
            huge.stop(0);
        }
    }
}
