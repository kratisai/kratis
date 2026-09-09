package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StringListConverterTest {

    private final StringListConverter converter = new StringListConverter();

    @Test
    void testConvertToDatabaseColumn() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(List.of("a", "b"))).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void testConvertToEntityAttribute() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
        assertThat(converter.convertToEntityAttribute("[\"a\",\"b\"]")).containsExactly("a", "b");

        assertThatThrownBy(() -> converter.convertToEntityAttribute("invalid json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Error reading list from JSON");
    }
}
