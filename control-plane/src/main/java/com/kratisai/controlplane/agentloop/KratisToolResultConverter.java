package com.kratisai.controlplane.agentloop;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.util.json.JsonParser;
import tools.jackson.core.type.TypeReference;

/**
 * Converts Kratis tool results to a form that is safe to send to every provider.
 *
 * <p>Spring AI's Google GenAI adapter parses a tool result into a structured
 * {@code function_response.response}. Gemini resolves a {@code $ref} or {@code $defs} key in that
 * payload as a multimodal part reference and rejects the request when the referenced name is
 * unknown, so a tool returning a JSON Schema document (for example {@code read_remote_file} on a
 * schema file) aborts the whole loop with HTTP 400. A {@code String} result that parses to JSON
 * carrying those keys is emitted as a JSON string instead, which keeps the keys inside a string
 * value where they are inert. Every other result is handed to {@link
 * DefaultToolCallResultConverter} unchanged, so ordinary JSON and structured results reach the
 * model exactly as before.
 */
public final class KratisToolResultConverter implements ToolCallResultConverter {

    private static final ToolCallResultConverter DEFAULT = new DefaultToolCallResultConverter();

    private static final String REF_KEY = "$ref";
    private static final String DEFS_KEY = "$defs";

    @Override
    public @NonNull String convert(@Nullable Object result, @Nullable Type returnType) {
        if (result instanceof String text) {
            return convertString(text);
        }
        return DEFAULT.convert(result, returnType);
    }

    private String convertString(String text) {
        Object parsed;
        try {
            parsed = JsonParser.fromJson(text, new TypeReference<Object>() {});
        } catch (RuntimeException e) {
            return JsonParser.getJsonMapper().writeValueAsString(text);
        }
        // Maintain raw JSON which doesn't contain a schema reference (illegal for Gemini)
        return containsSchemaReference(parsed) ? JsonParser.getJsonMapper().writeValueAsString(text) : text;
    }

    private boolean containsSchemaReference(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (REF_KEY.equals(entry.getKey())
                        || DEFS_KEY.equals(entry.getKey())
                        || containsSchemaReference(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                if (containsSchemaReference(item)) {
                    return true;
                }
            }
        }
        return false;
    }
}
