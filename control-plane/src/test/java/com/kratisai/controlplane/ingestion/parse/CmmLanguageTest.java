package com.kratisai.controlplane.ingestion.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CmmLanguageTest {
    @Test
    void mapsCmmLanguageIdsToNames() {
        assertThat(CmmLanguage.name(-1)).isNull();
        assertThat(CmmLanguage.name(0)).isEqualTo("GO");
        assertThat(CmmLanguage.name(30)).isEqualTo("YAML");
        assertThat(CmmLanguage.name(32)).isEqualTo("HCL");
        assertThat(CmmLanguage.name(34)).isEqualTo("DOCKERFILE");
        assertThat(CmmLanguage.name(169)).isEqualTo("DIAGRAM");
        assertThat(CmmLanguage.name(170)).isNull();
    }
}
