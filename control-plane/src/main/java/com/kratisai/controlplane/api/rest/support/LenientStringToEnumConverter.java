package com.kratisai.controlplane.api.rest.support;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;

/**
 * Converts query values to enums using the list-filter conventions of this API.
 * The {@code all} sentinel is a filter convention, not a domain constant; enums that genuinely declare
 * {@code ALL} must not use this converter.
 *
 * @param <T> the enum type to convert to
 */
public final class LenientStringToEnumConverter<T extends Enum<T>> implements Converter<String, T> {

    private static final Set<String> ABSENT_SENTINELS = Set.of("all");

    private final Class<T> enumType;

    public LenientStringToEnumConverter(Class<T> enumType) {
        this.enumType = enumType;
    }

    @Override
    public T convert(String source) {
        String trimmed = source.trim();
        if (trimmed.isEmpty() || ABSENT_SENTINELS.contains(trimmed.toLowerCase(Locale.ROOT))) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            String accepted =
                    Arrays.stream(enumType.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Unknown " + camelToSpaced(enumType.getSimpleName()) + ": " + source + ". Accepted values: "
                            + String.join(", ", ABSENT_SENTINELS) + ", " + accepted,
                    e);
        }
    }

    private static String camelToSpaced(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
    }
}
