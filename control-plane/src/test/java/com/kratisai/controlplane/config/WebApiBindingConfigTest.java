package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.UsageType;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.format.support.DefaultFormattingConversionService;

class WebApiBindingConfigTest {

    @Test
    void addFormatters_registersLenientConvertersOnlyForUsageFilters() {
        DefaultFormattingConversionService conversionService = new DefaultFormattingConversionService();
        new WebApiBindingConfig().addFormatters(conversionService);

        assertThat(conversionService.canConvert(String.class, UsageType.class)).isTrue();
        assertThat(conversionService.convert("execution", UsageType.class)).isEqualTo(UsageType.EXECUTION);
        assertThat(conversionService.convert("all", UsageType.class)).isNull();

        // Spring's default enum converter is still case-sensitive and has no sentinel:
        // a non-opted-in enum must not gain the lenient list-filter behaviour.
        assertThatThrownBy(() -> conversionService.convert("spec", CanvasType.class))
                .isInstanceOf(ConversionFailedException.class);
        assertThatThrownBy(() -> conversionService.convert("all", CanvasType.class))
                .isInstanceOf(ConversionFailedException.class);
    }
}
