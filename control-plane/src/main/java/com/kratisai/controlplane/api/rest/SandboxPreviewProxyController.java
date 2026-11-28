package com.kratisai.controlplane.api.rest;

import com.kratisai.controlplane.model.EnvironmentStatus;
import com.kratisai.controlplane.model.ExecutionEnvironment;
import com.kratisai.controlplane.repository.ExecutionEnvironmentRepository;
import com.kratisai.controlplane.service.SandboxPublishService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proxies a published sandbox preview to the user.
 *
 * <p>The address translation lives here: the user-facing URL is control-plane-relative
 * ({@code /preview/<envId>/<port>/**}) and this controller resolves the sandbox-local upstream
 * through {@link SandboxPublishService#previewUpstream}. The sandbox name and address never reach
 * the user. The URL is a capability — the environment id is an unguessable UUID and the preview is
 * ephemeral (the sandbox suspends when idle) — so the endpoint is permitAll for plain browser
 * navigation while the activity log that carries the link stays authenticated.
 *
 * <p>Only GET is proxied, and only while the environment is connected.
 */
@RestController
@RequestMapping("/preview/{environmentId}/{port}")
public class SandboxPreviewProxyController {

    private static final Logger logger = LoggerFactory.getLogger(SandboxPreviewProxyController.class);

    /** Hop-by-hop headers must not be forwarded across the proxy boundary. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length");

    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Hard cap on a proxied response body. The endpoint is unauthenticated and the sandbox app
     * controls the upstream response, so an unbounded body could exhaust control-plane heap. A
     * response larger than this is rejected rather than buffered.
     */
    private static final int MAX_PROXY_BODY_BYTES = 10 * 1024 * 1024;

    private final SandboxPublishService publishService;
    private final ExecutionEnvironmentRepository environmentRepository;
    private final HttpClient httpClient;

    public SandboxPreviewProxyController(
            SandboxPublishService publishService, ExecutionEnvironmentRepository environmentRepository) {
        this.publishService = publishService;
        this.environmentRepository = environmentRepository;
        // Redirect.NEVER: the sandbox app controls the upstream response, so following a 3xx would
        // make the control plane itself fetch whatever target the app names (SSRF to cloud
        // metadata, control-plane-host services, or other containers). Instead the 3xx is passed
        // through to the browser, which applies its own origin and scheme rules.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @GetMapping(value = "/**")
    public ResponseEntity<byte[]> proxy(
            @PathVariable UUID environmentId, @PathVariable int port, HttpServletRequest request)
            throws IOException, InterruptedException {
        if (port < 1 || port > 65535) {
            return ResponseEntity.badRequest().build();
        }
        ExecutionEnvironment environment =
                environmentRepository.findById(environmentId).orElse(null);
        if (environment == null || environment.getStatus() != EnvironmentStatus.CONNECTED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        Optional<String> upstream = publishService.previewUpstream(environmentId, port);
        if (upstream.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        String path = extractPath(request, environmentId, port);
        if (path.contains("..") || path.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        URI target = URI.create(upstream.get() + path);

        HttpRequest upstreamRequest =
                HttpRequest.newBuilder(target).timeout(UPSTREAM_TIMEOUT).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            logger.warn("Preview proxy failed to reach sandbox upstream {}: {}", target, e.toString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }

        byte[] body;
        try (InputStream upstreamBody = response.body()) {
            body = readBounded(upstreamBody, MAX_PROXY_BODY_BYTES);
        } catch (BodyTooLargeException e) {
            logger.warn("Preview proxy response from {} exceeded {} bytes", target, MAX_PROXY_BODY_BYTES);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        HttpHeaders headers = new HttpHeaders();
        response.headers().map().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, List.copyOf(values));
            }
        });
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.remove(HttpHeaders.TRANSFER_ENCODING);
        return ResponseEntity.status(response.statusCode()).headers(headers).body(body);
    }

    /** Strip the /preview/{envId}/{port} prefix, leaving the application path (never empty). */
    private String extractPath(HttpServletRequest request, UUID environmentId, int port) {
        String full = request.getRequestURI();
        String prefix = "/preview/" + environmentId + "/" + port;
        String rest = full.length() > prefix.length() ? full.substring(prefix.length()) : "";
        return rest.isEmpty() ? "/" : rest;
    }

    /**
     * Read at most {@code limit} bytes from {@code in}. Unlike {@code readAllBytes}, this never
     * buffers more than {@code limit + 1} bytes, so a sandbox app streaming an unbounded body
     * cannot exhaust control-plane heap through this unauthenticated endpoint.
     *
     * @throws BodyTooLargeException when the stream produces more than {@code limit} bytes
     */
    private static byte[] readBounded(InputStream in, int limit) throws IOException, BodyTooLargeException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new BodyTooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Signals that a proxied upstream response exceeded the configured body cap. */
    private static final class BodyTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
