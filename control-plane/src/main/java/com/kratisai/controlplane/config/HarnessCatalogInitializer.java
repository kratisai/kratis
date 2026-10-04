package com.kratisai.controlplane.config;

import com.kratisai.controlplane.model.AgentHarness;
import java.nio.file.Path;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Publishes the harness catalogue before any bean or scheduled task reads it. Must stay
 * {@link Lazy @Lazy(false)}: the bean is only a constructor side effect, and under
 * {@code spring.main.lazy-initialization=true} (test contexts) a lazy initializer never runs.
 */
@Component
@Lazy(false)
public class HarnessCatalogInitializer {

    public HarnessCatalogInitializer(HarnessesProperties properties) {
        AgentHarness.load(directory(properties));
    }

    private static Path directory(HarnessesProperties properties) {
        String configured = properties.getDirectory();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "kratis.harnesses.directory must name the directory holding the harness definitions");
        }
        return Path.of(configured);
    }
}
