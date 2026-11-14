package com.kratisai.controlplane.ingestion.surfaces;

import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.SurfaceRole;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class AssistedResponse {
    private AssistedResponse() {}

    record Discovery(List<Observation> surfaces) {
        Discovery {
            surfaces = List.copyOf(Objects.requireNonNull(surfaces));
        }
    }

    record Observation(UUID surfaceId, SurfaceRole role, String description, List<Fact> attributes) {
        Observation {
            Objects.requireNonNull(role);
            Objects.requireNonNull(description);
            attributes = List.copyOf(Objects.requireNonNull(attributes));
        }
    }

    record Fact(AttributeKind kind, String value, String file) {
        Fact {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(value);
            Objects.requireNonNull(file);
        }
    }
}
