package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the deploy compose wiring that lets the containerized control plane
 * orchestrate sandboxes on the host Docker daemon: socket mount, docker-group
 * access instead of a root user, and host-gateway URLs that sibling sandbox
 * containers can resolve (compose-network names like {@code litellm} cannot).
 */
class DeployComposeConfigTest {

    private static final Path DEPLOY_COMPOSE = findDeployCompose();

    @Test
    @SuppressWarnings("unchecked")
    void kratisService_mountsHostDockerSocket_withoutRunningAsRoot() {
        Map<String, Object> kratis = kratisService();

        assertThat((List<String>) kratis.get("volumes"))
                .as("LocalDockerSandboxProvider shells out to the docker CLI; without the socket "
                        + "every sandbox spawn fails inside the container")
                .contains("/var/run/docker.sock:/var/run/docker.sock");

        assertThat(kratis.get("user"))
                .as("the image's non-root USER must be preserved; socket access comes from the "
                        + "host docker group instead")
                .isNull();

        assertThat((List<String>) kratis.get("group_add"))
                .as("non-root socket access requires the host docker group id, detected by install.sh")
                .containsExactly("${DOCKER_GID:-0}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void kratisService_givesSandboxesHostGatewayUrls() {
        List<String> environment = (List<String>) kratisService().get("environment");

        assertThat(environment)
                .as("sandboxes are sibling containers on the host daemon and cannot resolve "
                        + "compose-network names, so the in-network LiteLLM URL must not be the "
                        + "one injected into sandboxes")
                .contains(
                        "KRATIS_LITELLM_BASE_URL=http://litellm:4000",
                        "KRATIS_LITELLM_SANDBOX_BASE_URL=http://host.docker.internal:${LITELLM_PORT:-4000}",
                        "KRATIS_SANDBOX_DOCKER_CONNECT_URL=ws://host.docker.internal:${API_PORT:-8080}/ws/env");
    }

    @Test
    @SuppressWarnings("unchecked")
    void kratisService_usesRegistryQualifiedRunnerImage() {
        List<String> environment = (List<String>) kratisService().get("environment");

        assertThat(environment)
                .as("docker run auto-pulls only registry-qualified images; the unqualified default "
                        + "would resolve to Docker Hub and fail")
                .contains("KRATIS_SANDBOX_DOCKER_RUNNER_IMAGE=ghcr.io/kratisai/kratis-runner-base:latest");
    }

    @Test
    @SuppressWarnings("unchecked")
    void kratisDbService_overridesContainerName_andDoesNotExposePortsByDefault() {
        try (InputStream in = Files.newInputStream(DEPLOY_COMPOSE)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> services = (Map<String, Object>) root.get("services");
            Map<String, Object> db = (Map<String, Object>) services.get("kratis-db");

            assertThat(db.get("container_name"))
                    .as("deploy compose sets container_name to kratis-postgres")
                    .isEqualTo("kratis-postgres");
            assertThat(db.get("ports"))
                    .as("database port should not be published to the host by default in deploy stack")
                    .isNull();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void kratisService_volumeLocationsDefaultToNamedVolumes_withEnvOverride() {
        Map<String, Object> root = loadRoot();
        Map<String, Object> services = (Map<String, Object>) root.get("services");

        assertThat((Map<String, Object>) root.get("volumes"))
                .as("every override falls back to a declared named volume, so an unset .env keeps "
                        + "the previous storage behavior")
                .containsKeys("kratis-data", "kratis-config", "kratis-blobs", "kratis-image-cache");

        List<String> dbVolumes = (List<String>) ((Map<String, Object>) services.get("kratis-db")).get("volumes");
        assertThat(dbVolumes)
                .as("KRATIS_DB_DIR must relocate PostgreSQL data to a host path")
                .contains("${KRATIS_DB_DIR:-kratis-data}:/var/lib/postgresql/data");

        assertThat((List<String>) kratisService().get("volumes"))
                .as("config and blobs must be relocatable to host directories")
                .contains(
                        "${KRATIS_CONFIG_DIR:-kratis-config}:/data/config",
                        "${KRATIS_BLOBS_DIR:-kratis-blobs}:/data/blobs");

        assertThat((List<String>) ((Map<String, Object>) services.get("registry-cache")).get("volumes"))
                .as("the registry cache must be relocatable like the other volumes")
                .contains("${KRATIS_REGISTRY_DIR:-kratis-image-cache}:/var/lib/registry");
    }

    @Test
    @SuppressWarnings("unchecked")
    void kratisService_defaultsGitHubAppKeyPathIntoTheConfigVolume() {
        List<String> environment = (List<String>) kratisService().get("environment");

        assertThat(environment)
                .as("dropping gh-app.pem into the config volume must be enough; the App stays "
                        + "disabled until app-id and app-name are set")
                .contains("KRATIS_GITHUB_PRIVATE_KEY_PATH=${KRATIS_GITHUB_PRIVATE_KEY_PATH:-/data/config/gh-app.pem}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> kratisService() {
        Map<String, Object> root = loadRoot();
        return (Map<String, Object>) ((Map<String, Object>) root.get("services")).get("kratis");
    }

    private static Map<String, Object> loadRoot() {
        try (InputStream in = Files.newInputStream(DEPLOY_COMPOSE)) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path findDeployCompose() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            Path candidate = dir.resolve("deploy/compose.yaml");
            if (Files.isRegularFile(candidate) && Files.isRegularFile(dir.resolve("control-plane/compose.yaml"))) {
                return candidate;
            }
            if (dir.getParent() == null) {
                break;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate deploy/compose.yaml from working directory "
                + Path.of("").toAbsolutePath());
    }
}
