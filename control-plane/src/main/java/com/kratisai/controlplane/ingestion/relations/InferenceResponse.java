package com.kratisai.controlplane.ingestion.relations;

import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.Nature;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record InferenceResponse(List<RelationProposal> matches) {
    public InferenceResponse {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
    }

    public record RelationProposal(
            @JsonDeserialize(using = LenientStringDeserializer.class)
            String subjectRepositoryId,

            @JsonDeserialize(using = LenientStringDeserializer.class)
            String subjectSurfaceId,

            @JsonDeserialize(using = LenientStringDeserializer.class)
            String otherRepositoryId,

            @JsonDeserialize(using = LenientStringDeserializer.class)
            String otherSurfaceId,

            Nature nature,
            Direction direction,
            Ceiling confidence,

            @JsonDeserialize(using = LenientStringDeserializer.class)
            String rationale,

            @JsonDeserialize(contentUsing = LenientStringDeserializer.class)
            List<String> subjectAttributeIds,

            @JsonDeserialize(contentUsing = LenientStringDeserializer.class)
            List<String> otherAttributeIds,

            @JsonDeserialize(contentUsing = LenientStringDeserializer.class)
            List<String> subjectSynopsisIds,

            @JsonDeserialize(contentUsing = LenientStringDeserializer.class)
            List<String> otherSynopsisIds) {}

    public static final class LenientStringDeserializer extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.START_ARRAY) {
                parser.skipChildren();
                return "[invalid]";
            }
            if (token == JsonToken.START_OBJECT) {
                parser.skipChildren();
                return "{invalid}";
            }
            if (token != null && token.isScalarValue()) {
                return parser.getValueAsString();
            }
            parser.skipChildren();
            return null;
        }
    }
}
