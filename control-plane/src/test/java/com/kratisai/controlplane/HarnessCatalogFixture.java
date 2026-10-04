package com.kratisai.controlplane;

import com.kratisai.controlplane.model.AgentHarness;
import java.nio.file.Path;

/**
 * Loads the shipped harness catalogue for tests that do not boot a Spring context. Spring tests get
 * the catalogue from {@code HarnessCatalogInitializer} instead.
 */
public final class HarnessCatalogFixture {

    /** The shipped catalogue, relative to the control-plane module (surefire's working directory). */
    public static final Path DIRECTORY = Path.of("harnesses");

    private HarnessCatalogFixture() {}

    public static void load() {
        AgentHarness.load(DIRECTORY);
    }
}
