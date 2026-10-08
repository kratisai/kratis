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

class BlobStorageConfigTest {

    private static final Path REPO_ROOT = repoRoot();

    @Test
    void applicationProperties_defaultsBlobRootToWorkspaceLocalDirectory() throws IOException {
        Properties props = new Properties();
        try (InputStream in =
                Files.newInputStream(REPO_ROOT.resolve("control-plane/src/main/resources/application.properties"))) {
            props.load(in);
        }

        assertThat(props.getProperty("kratis.storage.local-path"))
                .as("A development JVM cannot write /data (typically a root-owned mount), so the "
                        + "default must be workspace-local — ./data is gitignored — and "
                        + "KRATIS_STORAGE_LOCAL_PATH carries the deploy override")
                .isEqualTo("${KRATIS_STORAGE_LOCAL_PATH:./data/blobs}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deployCompose_pinsBlobRootToTheKratisBlobsVolume() {
        Map<String, Object> root = loadDeployCompose();
        Map<String, Object> services = (Map<String, Object>) root.get("services");
        Map<String, Object> kratis = (Map<String, Object>) services.get("kratis");

        assertThat((List<String>) kratis.get("environment"))
                .as("the workspace-local default must not leak into the container: blobs live in "
                        + "the kratis-blobs volume, and the container user cannot write wherever "
                        + "./data happens to resolve")
                .contains("KRATIS_STORAGE_LOCAL_PATH=/data/blobs");
        assertThat((List<String>) kratis.get("volumes"))
                .as("diff patches must survive container replacement")
                .contains("kratis-blobs:/data/blobs");
    }

    @Test
    void controlPlaneImage_preCreatesBlobRootSoTheNamedVolumeInheritsKratisOwnership() throws IOException {
        String dockerfile = Files.readString(REPO_ROOT.resolve("build/Dockerfile.control-plane"));

        assertThat(dockerfile)
                .as("Docker initializes an empty named volume from the image directory's ownership: "
                        + "/data/blobs must pre-exist as kratis (uid 1000) or the startup write "
                        + "probe fails against a root-owned volume")
                .contains("mkdir -p /data/config /data/blobs")
                .contains("chown -R kratis:kratis /data");
    }

    private static Map<String, Object> loadDeployCompose() {
        try (InputStream in = Files.newInputStream(REPO_ROOT.resolve("deploy/compose.yaml"))) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("deploy/compose.yaml"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException(
                    "Could not find deploy/compose.yaml above " + Path.of("").toAbsolutePath());
        }
        return directory;
    }
}
