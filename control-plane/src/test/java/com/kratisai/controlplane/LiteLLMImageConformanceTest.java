package com.kratisai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** A partial LiteLLM upgrade would run tests against a different proxy than the stacks ship. */
class LiteLLMImageConformanceTest {

    @Test
    void controlPlaneCompose_pinsTheSameLiteLLMImageAsTheTestContainer() {
        assertThat(digestOf(litellmImage(repoRoot().resolve("control-plane/compose.yaml"))))
                .isEqualTo(digestOf(PostgresTestInitializer.LITELLM_IMAGE));
    }

    @Test
    void deployCompose_pinsTheSameLiteLLMImageAsTheTestContainer() {
        assertThat(digestOf(litellmImage(repoRoot().resolve("deploy/compose.yaml"))))
                .isEqualTo(digestOf(PostgresTestInitializer.LITELLM_IMAGE));
    }

    private static Path repoRoot() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("deploy/compose.yaml"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException(
                    "Could not find deploy/compose.yaml above " + System.getProperty("user.dir"));
        }
        return directory;
    }

    private static String litellmImage(Path composeFile) {
        Map<?, ?> root = asMap(read(composeFile), composeFile);
        Map<?, ?> litellm = asMap(asMap(root.get("services"), composeFile).get("litellm"), composeFile);
        Object image = litellm.get("image");
        assertThat(image).as("services.litellm.image in %s", composeFile).isInstanceOf(String.class);
        return (String) image;
    }

    private static Object read(Path composeFile) {
        try {
            return new Yaml().load(Files.readString(composeFile));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<?, ?> asMap(Object value, Path composeFile) {
        assertThat(value).as("compose structure in %s", composeFile).isInstanceOf(Map.class);
        return (Map<?, ?>) value;
    }

    private static String digestOf(String image) {
        int separator = image.indexOf('@');
        assertThat(separator).as("image '%s' must be pinned by digest", image).isGreaterThan(0);
        return image.substring(separator + 1);
    }
}
