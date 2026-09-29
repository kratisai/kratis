package com.kratisai.controlplane.agentloop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.JsonParser;
import tools.jackson.core.type.TypeReference;

class KratisToolResultConverterTest {

    private final KratisToolResultConverter converter = new KratisToolResultConverter();

    @Test
    void convert_quotesJsonSchemaStringSoReferencesCannotResolve() {
        String schema = "{\"$defs\":{\"Annotations\":{\"type\":\"object\"}},\"$ref\":\"#/$defs/Annotations\"}";

        String wire = converter.convert(schema, String.class);

        assertThat(wire).isNotEqualTo(schema);
        assertThat(JsonParser.fromJson(wire, new TypeReference<Object>() {})).isEqualTo(schema);
    }

    @Test
    void convert_quotesNestedSchemaReferences() {
        String json = "[{\"ok\":true},{\"$ref\":\"#/$defs/Annotations\"}]";

        String wire = converter.convert(json, String.class);

        assertThat(wire).isNotEqualTo(json);
        assertThat(JsonParser.fromJson(wire, new TypeReference<Object>() {})).isEqualTo(json);
    }

    @Test
    void convert_passesRefFreeJsonThroughUnchanged() {
        String json = "{\"path\":\"src/Foo.java\",\"symbols\":[\"Foo\"]}";

        assertThat(converter.convert(json, String.class)).isEqualTo(json);
    }

    @Test
    void convert_passesJsonWithSchemaTokensInValuesThroughUnchanged() {
        String json = "{\"note\":\"mentions $ref and $defs but no schema keys\"}";

        assertThat(converter.convert(json, String.class)).isEqualTo(json);
    }

    @Test
    void convert_quotesPlainTextString() {
        assertThat(converter.convert("Document successfully written.", String.class))
                .isEqualTo("\"Document successfully written.\"");
    }

    @Test
    void convert_serializesRecordsWithoutRepackaging() {
        String wire = converter.convert(new Sample("value", 3), Sample.class);

        Map<String, Object> parsed = JsonParser.fromJson(wire, new TypeReference<Map<String, Object>>() {});
        assertThat(parsed).containsExactlyInAnyOrderEntriesOf(Map.of("content", "value", "depth", 3));
    }

    @Test
    void convert_serializesCollectionsOfRecords() {
        String wire = converter.convert(List.of(new Sample("a", 1), new Sample("b", 2)), List.class);

        assertThat(wire).contains("\"content\":\"a\"").contains("\"content\":\"b\"");
    }

    @Test
    void convert_nullResultSerializesAsNull() {
        assertThat(converter.convert(null, String.class)).isEqualTo("null");
    }

    record Sample(String content, int depth) {}
}
