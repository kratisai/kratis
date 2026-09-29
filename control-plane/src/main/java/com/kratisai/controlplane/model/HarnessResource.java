package com.kratisai.controlplane.model;

import java.util.Objects;

/**
 * A classpath resource shipped into a sandbox for a harness.
 *
 * <p>Harness definitions are pure data (they are extracted to JSON), so a harness cannot bundle
 * behaviour. Resources let a harness declare files the sandbox needs, and the provisioning service
 * materialises them before the setup commands run.
 *
 * @param source classpath path of the bundled file, e.g. {@code /gemini/patch.mjs}
 * @param target absolute path to write inside the sandbox, e.g. {@code /tmp/patch.mjs}
 */
public record HarnessResource(String source, String target) {

    public HarnessResource {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }
}
