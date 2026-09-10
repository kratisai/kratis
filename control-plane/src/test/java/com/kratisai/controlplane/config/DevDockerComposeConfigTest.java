package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DevDockerComposeConfigTest {

    private static final Path CONTROL_PLANE_DIR = findControlPlaneDir();

    @Test
    void dockerComposeStartSkip_never_forcesComposeUp_evenWhenSiblingServiceAlreadyRunning() throws IOException {
        Properties props = new Properties();
        Path appProperties = CONTROL_PLANE_DIR.resolve("src/main/resources/application.properties");
        try (InputStream in = Files.newInputStream(appProperties)) {
            props.load(in);
        }

        assertThat(props.getProperty("spring.docker.compose.start.skip"))
                .as("Spring Boot's default start.skip=IF_RUNNING skips `docker compose up` when ANY "
                        + "project service is already running, so a stopped/crashed litellm container "
                        + "would never be restarted (connection refused on :4000 at startup). NEVER "
                        + "forces `docker compose up` on every app start.")
                .isEqualTo("NEVER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void composeFile_definesBothServicesWithHealthchecks() {
        Map<String, Object> root = loadComposeFile();
        Map<String, Object> services = (Map<String, Object>) root.get("services");

        assertThat(services).containsKeys("kratis-db", "litellm");

        Map<String, Object> db = (Map<String, Object>) services.get("kratis-db");
        assertThat(hasHealthcheck(db))
                .as("kratis-db needs a healthcheck so litellm's depends_on: service_healthy can gate its startup")
                .isTrue();

        Map<String, Object> litellm = (Map<String, Object>) services.get("litellm");
        assertThat(hasHealthcheck(litellm))
                .as("litellm needs a healthcheck so compose can report it ready")
                .isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void composeFile_litellmStartsOnlyAfterDatabaseIsHealthy_andPublishesPort4000() {
        Map<String, Object> root = loadComposeFile();
        Map<String, Object> services = (Map<String, Object>) root.get("services");
        Map<String, Object> litellm = (Map<String, Object>) services.get("litellm");

        Map<String, Object> dependsOn = (Map<String, Object>) litellm.get("depends_on");
        assertThat(dependsOn).isNotNull();
        Map<String, Object> dbCondition = (Map<String, Object>) dependsOn.get("kratis-db");
        assertThat(dbCondition).isNotNull();
        assertThat(dbCondition.get("condition")).isEqualTo("service_healthy");

        assertThat((List<String>) litellm.get("ports"))
                .as("kratis.litellm.base-url defaults to http://localhost:4000")
                .contains("4000:4000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void composeFile_overridesDevelopmentContainerNames() {
        Map<String, Object> root = loadComposeFile();
        assertThat(root.get("name")).isEqualTo("kratis-dev");

        Map<String, Object> services = (Map<String, Object>) root.get("services");
        Map<String, Object> db = (Map<String, Object>) services.get("kratis-db");
        assertThat(db.get("container_name"))
                .as("kratis-db container name should be overridden to kratis-dev-postgres")
                .isEqualTo("kratis-dev-postgres");

        Map<String, Object> litellm = (Map<String, Object>) services.get("litellm");
        assertThat(litellm.get("container_name"))
                .as("litellm container name should be overridden to kratis-dev-litellm")
                .isEqualTo("kratis-dev-litellm");
    }

    @Test
    void applicationProperties_definesHostAndSandboxLiteLLMUrls() throws IOException {
        Properties props = new Properties();
        try (InputStream in =
                Files.newInputStream(CONTROL_PLANE_DIR.resolve("src/main/resources/application.properties"))) {
            props.load(in);
        }

        assertThat(props.getProperty("kratis.litellm.base-url"))
                .as("Host-side URL used by the control plane (matches the 4000:4000 port published in compose.yaml)")
                .isEqualTo("${KRATIS_LITELLM_BASE_URL:http://localhost:4000}");
        assertThat(props.getProperty("kratis.litellm.sandbox-base-url"))
                .as("URL injected into sandbox containers, which reach the host via host.docker.internal; "
                        + "KRATIS_LITELLM_SANDBOX_BASE_URL must override it independently of base-url so deployed "
                        + "stacks can use an in-network base-url that sandboxes cannot resolve")
                .isEqualTo(
                        "${KRATIS_LITELLM_SANDBOX_BASE_URL:${KRATIS_LITELLM_BASE_URL:http://host.docker.internal:4000}}");
    }

    private static Map<String, Object> loadComposeFile() {
        try (InputStream in = Files.newInputStream(CONTROL_PLANE_DIR.resolve("compose.yaml"))) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean hasHealthcheck(Map<String, Object> service) {
        Object healthcheck = service.get("healthcheck");
        if (!(healthcheck instanceof Map<?, ?> map)) {
            return false;
        }
        Object test = map.get("test");
        return (test instanceof List<?> list && !list.isEmpty()) || (test instanceof String s && !s.isBlank());
    }

    private static Path findControlPlaneDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.isRegularFile(dir.resolve("compose.yaml"))
                    && Files.isRegularFile(dir.resolve("src/main/resources/application.properties"))) {
                return dir;
            }
            dir = dir.getParent();
            if (dir == null) {
                break;
            }
        }
        throw new IllegalStateException("Could not locate control-plane module from working directory "
                + Path.of("").toAbsolutePath());
    }
}
