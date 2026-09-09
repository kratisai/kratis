package com.kratisai.controlplane;

import static java.net.URI.create;

import com.github.dockerjava.api.command.RestartContainerCmd;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Singleton Testcontainers initializer. Starts PostgreSQL, LiteLLM, and Mock LLM containers once
 * per JVM run. All containers use reuse-enabled configuration — with TESTCONTAINERS_REUSE_ENABLE=true
 * and ~/.testcontainers.properties containing testcontainers.reuse.enable=true, containers persist
 * between test runs for near-instant startup.
 *
 * <p>Schema isolation: each call to {@link #initialize} (one per Spring context) allocates a
 * fresh, randomly-named Postgres schema (e.g. {@code test_a1b2c3d4e5f6}). The schema properties
 * are injected into the context's local {@code Environment} — not as global System properties —
 * so concurrent contexts never bleed data into each other. The schema is dropped automatically
 * when the JVM shuts down.
 *
 * <p>No shared Docker network is used — containers connect via host:port to avoid creating
 * orphaned networks on every test run.
 */
public class PostgresTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger logger = LoggerFactory.getLogger(PostgresTestInitializer.class);

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES_CONTAINER = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16"))
            .withDatabaseName("kratis_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand(
                    "postgres",
                    "-c",
                    "max_connections=1000",
                    "-c",
                    "fsync=off",
                    "-c",
                    "synchronous_commit=off",
                    "-c",
                    "full_page_writes=off")
            .withLabel("workspace.id", System.getProperty("user.dir"))
            .withReuse(true);

    static final GenericContainer<?> LITELLM_CONTAINER;

    static {
        // 1. Start PostgreSQL (reused across test runs when reuse is enabled)
        POSTGRES_CONTAINER.start();

        // Datasource coordinates are global — all Spring contexts talk to the same Postgres
        // instance. Schema isolation (below) is what separates their data.
        System.setProperty("spring.datasource.url", POSTGRES_CONTAINER.getJdbcUrl());
        System.setProperty("spring.datasource.username", POSTGRES_CONTAINER.getUsername());
        System.setProperty("spring.datasource.password", POSTGRES_CONTAINER.getPassword());
        System.setProperty("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        System.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");

        // Ensure pgvector is installed in the public schema so all isolated test schemas can access it
        try (Connection conn = DriverManager.getConnection(
                        POSTGRES_CONTAINER.getJdbcUrl(),
                        POSTGRES_CONTAINER.getUsername(),
                        POSTGRES_CONTAINER.getPassword());
                java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;");
            logger.info("✅ Ensured pgvector extension exists in public schema");
        } catch (Exception e) {
            throw new RuntimeException("Failed to install pgvector in public schema", e);
        }

        // Detect the Docker bridge gateway IP — reachable from both the host and all containers
        // on the default bridge network. This eliminates the need for separate "docker-accessible"
        // URLs since a single baseUrl works for all consumers (control plane, LiteLLM, agents).
        String dockerGatewayIp = POSTGRES_CONTAINER
                .getContainerInfo()
                .getNetworkSettings()
                .getNetworks()
                .values()
                .iterator()
                .next()
                .getGateway();
        logger.info("Detected Docker bridge gateway IP: {}", dockerGatewayIp);

        // Use the gateway IP so LiteLLM can reach Postgres without a shared network
        String databaseUrl =
                "postgresql://test:test@" + dockerGatewayIp + ":" + POSTGRES_CONTAINER.getMappedPort(5432) + "/litellm";

        // Start LiteLLM from pre-built cached image (reused across test runs)
        // TestLiteLLMClient tracks models registered per test, and expunges them after each test.
        LITELLM_CONTAINER = new GenericContainer<>(DockerImageName.parse("ghcr.io/berriai/litellm:main-stable"))
                .withExposedPorts(4000)
                .withExtraHost("host.docker.internal", "host-gateway")
                .withLabel("workspace.id", System.getProperty("user.dir"))
                .withEnv("LITELLM_MASTER_KEY", "sk-test-master")
                .withEnv("LITELLM_LOG", "INFO")
                .withEnv("STORE_MODEL_IN_DB", "True")
                .withEnv("DATABASE_URL", databaseUrl)
                .withCopyToContainer(
                        MountableFile.forClasspathResource("litellm/litellm-config.yaml"), "/app/config.yaml")
                .withCopyToContainer(
                        MountableFile.forClasspathResource("litellm/patch_response_logging.py"),
                        "/tmp/patch_response_logging.py")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(
                        "/bin/sh",
                        "-c",
                        "/app/.venv/bin/python3 /tmp/patch_response_logging.py"
                                + " && exec litellm --port 4000 --config /app/config.yaml"))
                .withCommand("--port", "4000", "--config", "/app/config.yaml")
                .withReuse(true)
                .waitingFor(Wait.forHttp("/health/readiness").forPort(4000).withStartupTimeout(Duration.ofMinutes(3)));

        logger.info("Starting LiteLLM proxy...");
        LITELLM_CONTAINER.start();

        // Use the Docker gateway IP so both the control plane (host) and sandbox containers
        // can reach LiteLLM via the same URL — no separate "docker-accessible" URL needed
        String litellmGatewayUrl = "http://" + dockerGatewayIp + ":" + LITELLM_CONTAINER.getMappedPort(4000);
        System.setProperty("kratis.litellm.base-url", litellmGatewayUrl);
        System.setProperty("kratis.litellm.master-key", "sk-test-master");
        logger.info("LiteLLM operational at {}", litellmGatewayUrl);

        verifyLiteLLMLoggingPatch();
        checkLiteLLMModelCountOrFail(litellmGatewayUrl);

        // Register shutdown hook to check model count and conditionally remove container
        // This prevents unbounded growth across test runs
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(() -> executeLiteLLMShutdownHook(dockerGatewayIp), "litellm-cleanup-check"));
    }

    static final long LITELLM_MODEL_COUNT_FAIL_THRESHOLD = 100;

    private static void verifyLiteLLMLoggingPatch() {
        try {
            LITELLM_CONTAINER.copyFileToContainer(
                    MountableFile.forClasspathResource("litellm/patch_response_logging.py"),
                    "/tmp/patch_response_logging_check.py");
            Container.ExecResult result = LITELLM_CONTAINER.execInContainer(
                    "/app/.venv/bin/python3", "/tmp/patch_response_logging_check.py", "--check");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("LiteLLM logging patch drift detected: " + result.getStdout()
                        + System.lineSeparator() + result.getStderr());
            }
            logger.info("LiteLLM logging patch matches the running image");
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Unable to verify the LiteLLM logging patch", e);
        }
    }

    static void checkLiteLLMModelCountOrFail(String litellmGatewayUrl) {
        long modelCount;
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(create(litellmGatewayUrl + "/model/info"))
                    .header("Authorization", "Bearer sk-test-master")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn(
                        "Failed to check LiteLLM model count at startup: HTTP {} | {}",
                        response.statusCode(),
                        response.body());
                return;
            }
            modelCount = response.body().split("\"model_name\"").length - 1;
            logger.info("LiteLLM model count at startup: {}", modelCount);
        } catch (Exception e) {
            // Network/IO problems here are an infra concern, not evidence of model pollution.
            // Don't fail the suite for this — just warn and proceed.
            logger.warn("Failed to check LiteLLM model count at startup: {}", e.getMessage());
            return;
        }

        if (modelCount > LITELLM_MODEL_COUNT_FAIL_THRESHOLD) {
            throw new IllegalStateException(
                    String.format("""

                    ============================================================================
                    TEST ENVIRONMENT ERROR: LiteLLM model table is polluted with %d models
                    (threshold: %d).
                    ============================================================================
                    The shared, reused LiteLLM Testcontainer stores its models in the same
                    reused Postgres container the control plane uses (STORE_MODEL_IN_DB=true).
                    Models registered by previous test runs that were never cleaned up (e.g. a
                    crashed/killed run) accumulate indefinitely across runs because reusing the
                    container also reuses its Postgres-backed model table.

                    With this many models registered, every /model/info call — used by
                    verifyModelRegistered() in the sandbox dispatch path — returns a multi-
                    megabyte JSON payload that takes seconds to serialize/transfer/parse, which
                    causes unrelated-looking, intermittent WebSocket dispatch timeouts across
                    the integration test suite (NOT a "flaky test" — this is why).

                    Fix: truncate the LiteLLM model table before re-running the suite:
                      docker exec <postgres-container> psql -U test -d litellm -c \
                        'TRUNCATE TABLE "LiteLLM_ProxyModelTable"'

                    Or remove the reused containers entirely to start fresh:
                      docker ps -a --filter "label=workspace.id=%s" -q | xargs -r docker rm -f
                    ============================================================================
                    """, modelCount, LITELLM_MODEL_COUNT_FAIL_THRESHOLD, System.getProperty("user.dir")));
        }
    }

    public static void executeLiteLLMShutdownHook(String dockerGatewayIp) {
        try {
            String url = "http://" + dockerGatewayIp + ":" + LITELLM_CONTAINER.getMappedPort(4000);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(create(url + "/model/info"))
                    .header("Authorization", "Bearer sk-test-master")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "LiteLLM connection failed with error: " + response.statusCode() + " | " + response.body());
            }
            // Simple check: count occurrences of "model_name" in response
            long modelCount = response.body().split("model_name").length - 1;
            logger.info("LiteLLM model count at shutdown: {}", modelCount);

            if (modelCount > LITELLM_MODEL_COUNT_FAIL_THRESHOLD) {
                logger.warn(
                        "LiteLLM has {} models (>{} threshold) — truncating LiteLLM_ProxyModelTable in Postgres"
                                + " and restarting the container so the next test run starts clean",
                        modelCount,
                        LITELLM_MODEL_COUNT_FAIL_THRESHOLD);
                truncateLiteLLMModelTable();
                restartLiteLLMContainer();
            }
        } catch (Exception e) {
            logger.warn("Failed to check LiteLLM model count at shutdown: {}", e.getMessage());
        }
    }

    private static void truncateLiteLLMModelTable() {
        String jdbcUrl = POSTGRES_CONTAINER.getJdbcUrl();
        int queryStart = jdbcUrl.indexOf('?');
        String cleanUrl = queryStart >= 0 ? jdbcUrl.substring(0, queryStart) : jdbcUrl;
        int lastSlash = cleanUrl.lastIndexOf('/');
        String litellmDbUrl = cleanUrl.substring(0, lastSlash + 1) + "litellm";

        try (Connection conn = DriverManager.getConnection(
                        litellmDbUrl, POSTGRES_CONTAINER.getUsername(), POSTGRES_CONTAINER.getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE \"LiteLLM_ProxyModelTable\"");
            logger.info("✅ Truncated LiteLLM_ProxyModelTable");
        } catch (Exception e) {
            logger.warn("Failed to truncate LiteLLM_ProxyModelTable: {}", e.getMessage());
        }
    }

    private static void restartLiteLLMContainer() {
        try (RestartContainerCmd cmd = LITELLM_CONTAINER
                .getDockerClient()
                .restartContainerCmd(LITELLM_CONTAINER.getContainerId())
                .withTimeout(10)) {
            cmd.exec();
            logger.info("✅ Restarted LiteLLM container — next test run will see a clean, empty model registry");
        } catch (Exception e) {
            logger.warn("Failed to restart LiteLLM container: {}", e.getMessage());
        }
    }

    /**
     * Called once per Spring {@code ApplicationContext} creation. Allocates a unique Postgres
     * schema for this context, creates it, and injects schema-scoped properties into the context's
     * local environment.
     *
     * <p>Using the context's {@code Environment} (not {@code System.setProperty}) means each
     * context sees its own schema name, with no interference between concurrently-running contexts.
     */
    @Override
    public void initialize(@NonNull ConfigurableApplicationContext applicationContext) {
        String schema = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        createSchema(schema);
        registerSchemaDropHook(schema);

        String schemaJdbcUrl = buildSchemaBoundJdbcUrl(POSTGRES_CONTAINER.getJdbcUrl(), schema);
        applicationContext
                .getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "testSchemaConfig",
                        Map.of(
                                "test.schema", schema,
                                "spring.datasource.url", schemaJdbcUrl,
                                "spring.datasource.hikari.pool-name", "kratis-test-" + schema,
                                "spring.liquibase.enabled", "true",
                                "spring.liquibase.default-schema", schema,
                                "spring.liquibase.liquibase-schema", schema,
                                "spring.jpa.properties.hibernate.default_schema", schema)));

        logger.info("🗄  Test schema '{}' allocated for Spring context '{}'", schema, applicationContext.getId());
    }

    /**
     * Builds a JDBC URL that binds connections to a specific Postgres schema via
     * {@code currentSchema}. This replaces the previous {@code connection-init-sql} hack that
     * set {@code search_path} on every Hikari connection. Using Spring's standard datasource
     * configuration lets Hikari and Hibernate manage the schema properly.
     */
    static String buildSchemaBoundJdbcUrl(String jdbcUrl, String schema) {
        int queryStart = jdbcUrl.indexOf('?');
        String base = queryStart >= 0 ? jdbcUrl.substring(0, queryStart) : jdbcUrl;
        String query = queryStart >= 0 ? jdbcUrl.substring(queryStart + 1) : "";
        Map<String, String> params = new java.util.LinkedHashMap<>();
        if (!query.isBlank()) {
            for (String pair : query.split("&")) {
                if (!pair.isBlank()) {
                    int idx = pair.indexOf('=');
                    if (idx >= 0) {
                        params.put(pair.substring(0, idx), pair.substring(idx + 1));
                    } else {
                        params.put(pair, "");
                    }
                }
            }
        }
        params.put("currentSchema", schema + ",public");
        String joined = params.entrySet().stream()
                .map(e -> e.getValue().isEmpty() ? e.getKey() : e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining("&"));
        return base + "?" + joined;
    }

    // The schema name is a trusted config value (a generated UUID), not tainted input.
    @SuppressFBWarnings("SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE")
    private void createSchema(String schema) {
        try (Connection conn = DriverManager.getConnection(
                        POSTGRES_CONTAINER.getJdbcUrl(),
                        POSTGRES_CONTAINER.getUsername(),
                        POSTGRES_CONTAINER.getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create test schema '" + schema + "'", ex);
        }
    }

    private void registerSchemaDropHook(String schema) {
        String jdbcUrl = POSTGRES_CONTAINER.getJdbcUrl();
        String username = POSTGRES_CONTAINER.getUsername();
        String password = POSTGRES_CONTAINER.getPassword();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(() -> dropSchema(jdbcUrl, username, password, schema), "drop-schema-" + schema));
    }

    // The schema name is a trusted config value (a generated UUID), not tainted input.
    @SuppressFBWarnings("SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE")
    private static void dropSchema(String jdbcUrl, String username, String password, String schema) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            logger.info("🗑  Dropped test schema '{}'", schema);
        } catch (Exception ex) {
            logger.warn("Failed to drop test schema '{}': {}", schema, ex.getMessage());
        }
    }
}
