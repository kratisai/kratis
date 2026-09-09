package com.kratisai.controlplane.git.provider;

import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Collects all registered {@link RepoProvider} beans and dispatches by {@link RepositoryType}.
 *
 * <p>Repositories with {@link CredentialType#SSH_KEY} or no credentials (unauthenticated) are
 * always routed to the {@code GENERIC} provider, because SSH and anonymous wire access are transports,
 * not REST API providers — the REST APIs of the specific hosts are never called without a token.
 */
@Component
public class RepoProviderRegistry {

    private final Map<RepositoryType, RepoProvider> providersByType;

    public RepoProviderRegistry(List<RepoProvider> providers) {
        Map<RepositoryType, RepoProvider> map = new HashMap<>();
        List<RepoProvider> resolved = providers != null ? providers : List.of();
        for (RepoProvider provider : resolved) {
            RepositoryType type = Objects.requireNonNull(
                    provider.supportedType(), provider.getClass().getName() + ".supportedType() must not be null");
            RepoProvider previous = map.putIfAbsent(type, provider);
            if (previous != null) {
                throw new IllegalStateException("Duplicate RepoProvider registration for type " + type + ": "
                        + previous.getClass().getName() + " and "
                        + provider.getClass().getName());
            }
        }
        this.providersByType = Map.copyOf(map);
    }

    public RepoProvider getProvider(Repository repo) {
        if (repo.getCredential() == null || repo.getCredential().getType() == CredentialType.SSH_KEY) {
            RepoProvider gitProtocol = providersByType.get(RepositoryType.GENERIC);
            if (gitProtocol != null) {
                return gitProtocol;
            }
        }
        return getProvider(repo.getRepositoryType());
    }

    public RepoProvider getProvider(RepositoryType type) {
        RepoProvider provider = providersByType.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("No RepoProvider registered for repository type: " + type);
        }
        return provider;
    }

    Set<RepositoryType> supportedTypes() {
        return providersByType.keySet();
    }
}
