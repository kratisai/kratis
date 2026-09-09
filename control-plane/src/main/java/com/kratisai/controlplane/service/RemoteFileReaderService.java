package com.kratisai.controlplane.service;

import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.provider.RepoProvider;
import com.kratisai.controlplane.git.provider.RepoProviderRegistry;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import java.net.URI;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemoteFileReaderService {

    private final RepositoryRepository repositoryRepository;
    private final GitCredentialResolver credentialResolver;
    private final RepoProviderRegistry providerRegistry;

    public RemoteFileReaderService(
            RepositoryRepository repositoryRepository,
            GitCredentialResolver credentialResolver,
            RepoProviderRegistry providerRegistry) {
        this.repositoryRepository = repositoryRepository;
        this.credentialResolver = credentialResolver;
        this.providerRegistry = providerRegistry;
    }

    @Transactional(readOnly = true)
    public String readFile(UUID teamId, String repoName, String filePath, String branch) {
        if (filePath.contains("..")) {
            throw new IllegalArgumentException("Path traversal is not allowed.");
        }

        Repository repo = repositoryRepository
                .findByTeamIdAndName(teamId, repoName)
                .orElseThrow(() ->
                        new IllegalArgumentException("Repository '" + repoName + "' not found for team " + teamId));

        GitAuthMaterial auth = credentialResolver.resolve(repo);
        RepoProvider provider = providerRegistry.getProvider(repo);
        return provider.readFile(repo, auth, filePath, branch);
    }

    public static URI buildGitHubContentsUrl(String owner, String repo, String path, String ref) {
        return URI.create("https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path + "?ref=" + ref);
    }
}
