package com.kratisai.controlplane.model;

import java.util.Objects;

/**
 * A file a harness declares for its sandbox. Definitions are pure data, so a harness cannot bundle
 * behaviour; resources are materialised before its setup commands run.
 *
 * @param source path of the file relative to the harness directory, e.g. {@code gemini/patch.mjs}
 * @param target absolute path to write inside the sandbox, e.g. {@code /tmp/patch.mjs}
 */
public record HarnessResource(String source, String target) {

    public HarnessResource {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }
}
