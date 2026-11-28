package com.kratisai.controlplane.api.rest.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.api.restdto.UsageLogStatus;
import com.kratisai.controlplane.model.UsageType;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LenientStringToEnumConverterTest {

    @Test
    void convert_acceptsBlankAndAbsentSentinel() {
        LenientStringToEnumConverter<UsageType> converter = new LenientStringToEnumConverter<>(UsageType.class);

        assertThat(converter.convert("")).isNull();
        assertThat(converter.convert("   ")).isNull();
        assertThat(converter.convert("all")).isNull();
        assertThat(converter.convert("ALL")).isNull();
        assertThat(converter.convert(" All ")).isNull();
        assertThat(new LenientStringToEnumConverter<>(UsageLogStatus.class).convert("all"))
                .isNull();
    }

    @Test
    void convert_matchesEnumNamesCaseInsensitivelyAndWithWhitespace() {
        LenientStringToEnumConverter<UsageType> converter = new LenientStringToEnumConverter<>(UsageType.class);

        assertThat(converter.convert("TEAM_WIKI")).isEqualTo(UsageType.TEAM_WIKI);
        assertThat(converter.convert("team_wiki")).isEqualTo(UsageType.TEAM_WIKI);
        assertThat(converter.convert("  Chat ")).isEqualTo(UsageType.CHAT);
    }

    @Test
    void convert_usesRootLocaleForCaseNormalization() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(new LenientStringToEnumConverter<>(UsageLogStatus.class).convert("idle"))
                    .isEqualTo(UsageLogStatus.IDLE);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void convert_rejectsUnknownValuesWithAcceptedNames() {
        assertThatThrownBy(() -> new LenientStringToEnumConverter<>(UsageType.class).convert("WIDGET"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown usage type: WIDGET")
                .hasMessageContaining("EXECUTION")
                .hasMessageContaining("TEAM_WIKI")
                .hasMessageNotContaining("No enum constant");

        assertThatThrownBy(() -> new LenientStringToEnumConverter<>(UsageLogStatus.class).convert("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
