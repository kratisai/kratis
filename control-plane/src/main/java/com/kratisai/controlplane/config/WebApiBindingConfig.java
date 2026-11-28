package com.kratisai.controlplane.config;

import com.kratisai.controlplane.api.rest.support.LenientStringToEnumConverter;
import com.kratisai.controlplane.api.restdto.UsageLogStatus;
import com.kratisai.controlplane.model.UsageType;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers lenient query binding only for the usage-log filter enums.
 *
 * <p>These list filters accept case-insensitive, whitespace-tolerant values and use {@code all}
 * to represent no filter. Keeping registration explicit prevents those conventions from changing
 * binding behavior for unrelated domain enums.
 */
@Configuration
public class WebApiBindingConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, UsageType.class, new LenientStringToEnumConverter<>(UsageType.class));
        registry.addConverter(
                String.class, UsageLogStatus.class, new LenientStringToEnumConverter<>(UsageLogStatus.class));
    }
}
