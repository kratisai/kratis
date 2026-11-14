package com.kratisai.controlplane.ingestion.relations;

import com.kratisai.controlplane.model.RepoRole;
import java.util.List;

/** The synopsis the model returns in SYNTHESIZE_SYNOPSIS; citations name the inputs it used. */
public record SynopsisResponse(
        RepoRole repoRole,
        List<String> knownAs,
        String domain,
        String interfaces,
        String deployment,
        Citations citations) {
    public SynopsisResponse {
        validateRole(repoRole);
        knownAs = knownAs == null ? List.of() : List.copyOf(knownAs.subList(0, Math.min(knownAs.size(), 8)));
        domain = domain == null ? "" : domain;
        interfaces = interfaces == null ? "" : interfaces;
        deployment = deployment == null ? "" : deployment;
        citations = citations == null ? new Citations(List.of(), null, List.of(), List.of(), List.of()) : citations;
    }

    private static void validateRole(RepoRole repoRole) {
        if (repoRole == null) {
            throw new IllegalArgumentException("repoRole is required; use one of the listed roles.");
        }
    }

    public record Citations(
            List<String> dimensionIds,
            String wikiSlug,
            List<String> filePaths,
            List<String> surfaceIds,
            List<String> attributeIds) {
        public Citations {
            dimensionIds = dimensionIds == null ? List.of() : List.copyOf(dimensionIds);
            filePaths = filePaths == null ? List.of() : List.copyOf(filePaths);
            surfaceIds = surfaceIds == null ? List.of() : List.copyOf(surfaceIds);
            attributeIds = attributeIds == null ? List.of() : List.copyOf(attributeIds);
        }
    }

    public boolean sectionsBlank() {
        return domain.isBlank() || interfaces.isBlank() || deployment.isBlank();
    }
}
