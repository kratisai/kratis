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
            role = role == null ? SurfaceRole.UNKNOWN : role;
            Objects.requireNonNull(description);
            attributes = List.copyOf(Objects.requireNonNull(attributes));
        }
    }

    record Fact(AttributeKind kind, String value, String file) {
        Fact {
            if (kind == null) {
                throw new IllegalArgumentException("Fact.kind is required");
            }
            if (kind == AttributeKind.FILE && file == null && value != null) {
                file = value;
            } else if (kind == AttributeKind.FILE && file != null && value == null) {
                value = file;
            }
            if (value == null) {
                throw new IllegalArgumentException("Fact.value is required");
            }
            if (file == null) {
                throw new IllegalArgumentException("Fact.file is required (repository-relative path)");
            }
        }
    }
}
