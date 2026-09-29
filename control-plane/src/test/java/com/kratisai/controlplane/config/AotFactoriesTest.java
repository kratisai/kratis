package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.support.SpringFactoriesLoader;

@DisplayName("AOT Factories Configuration")
class AotFactoriesTest {

    @Test
    @DisplayName("registers AotHints, SqliteRuntimeHintsRegistrar, and LiquibaseRuntimeHintsRegistrar in aot.factories")
    void loadsRuntimeHintsRegistrarsFromAotFactories() {
        List<RuntimeHintsRegistrar> registrars = SpringFactoriesLoader.forResourceLocation(
                        "META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class);

        assertThat(registrars)
                .hasAtLeastOneElementOfType(AotHints.class)
                .hasAtLeastOneElementOfType(SqliteRuntimeHintsRegistrar.class)
                .hasAtLeastOneElementOfType(LiquibaseRuntimeHintsRegistrar.class);
    }
}
