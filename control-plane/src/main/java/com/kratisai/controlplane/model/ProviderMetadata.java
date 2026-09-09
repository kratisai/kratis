package com.kratisai.controlplane.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * Value object encapsulating provider-specific metadata for a repository credential. Avoids leaking
 * raw JSON string parsing or generic JsonNode (meta blob) abstractions into controllers and
 * services.
 */
public record ProviderMetadata(String rawJson) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static ProviderMetadata of(String rawJson) {
        return new ProviderMetadata(rawJson == null ? "" : rawJson.trim());
    }

    private Optional<JsonNode> getJsonNode() {
        if (rawJson.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.readTree(rawJson));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<String> textValue(String key) {
        return getJsonNode()
                .filter(node -> node.has(key))
                .map(node -> node.get(key).asText().trim())
                .filter(v -> !v.isEmpty());
    }

    /** Retrieves the GitHub App installation ID if present. */
    public Optional<String> getInstallationId() {
        return textValue("installationId");
    }

    /** Retrieves the GitLab base URL if configured. */
    public Optional<String> getGitLabUrl() {
        return textValue("gitlabUrl");
    }

    /** Retrieves the Bitbucket workspace if configured. */
    public Optional<String> getBitbucketWorkspace() {
        return textValue("bitbucketWorkspace");
    }

    /** Retrieves the Azure DevOps API base URL (e.g. {@code https://dev.azure.com/myorg}) if configured. */
    public Optional<String> getAzureBaseUrl() {
        return textValue("azureBaseUrl");
    }

    /** Retrieves the Azure DevOps project name if configured. */
    public Optional<String> getAzureProject() {
        return textValue("azureProject");
    }

    /** Checks if this credential corresponds to a GitHub App installation. */
    public boolean isGitHubApp() {
        return getInstallationId().isPresent();
    }

    /** Retrieves the provider name if configured (e.g. github, gitlab). */
    public Optional<String> getProvider() {
        return textValue("provider");
    }

    /**
     * Maps the stored {@code provider} key to its {@link RepositoryType}, allowing a credential to
     * be queried without any linked repository.
     */
    public Optional<RepositoryType> getProviderType() {
        return getProvider().map(ProviderMetadata::mapProviderType);
    }

    static RepositoryType mapProviderType(String provider) {
        if (provider == null) {
            return null;
        }
        return switch (provider.trim().toLowerCase()) {
            case "github" -> RepositoryType.GITHUB;
            case "gitlab" -> RepositoryType.GITLAB;
            case "bitbucket" -> RepositoryType.BITBUCKET;
            case "azure" -> RepositoryType.AZURE;
            default -> null;
        };
    }
}
