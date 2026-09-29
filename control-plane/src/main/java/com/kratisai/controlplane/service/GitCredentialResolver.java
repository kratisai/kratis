package com.kratisai.controlplane.service;

import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.provider.GitHubApiService;
import com.kratisai.controlplane.model.CredentialType;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import org.springframework.stereotype.Component;

@Component
public class GitCredentialResolver {

    private final CredentialService credentialService;
    private final GitHubApiService gitHubApiService;

    public GitCredentialResolver(CredentialService credentialService, GitHubApiService gitHubApiService) {
        this.credentialService = credentialService;
        this.gitHubApiService = gitHubApiService;
    }

    public GitAuthMaterial resolve(Repository repository) {
        return resolve(repository.getCredential());
    }

    public GitAuthMaterial resolve(RepoCredential credential) {
        if (credential == null) {
            return GitAuthMaterial.none();
        }

        CredentialType type = credential.getType();
        if (type == CredentialType.SSH_KEY) {
            String privateKey = credentialService.decryptSecret(credential);
            if (privateKey == null || privateKey.trim().isEmpty()) {
                throw new IllegalStateException(
                        "SSH_KEY credential decryption failed or produced empty key: " + credential.getName());
            }
            return GitAuthMaterial.ofSshKey(privateKey);
        }

        if (type == CredentialType.GITHUB_APP) {
            String installationId = credential
                    .getMetadata()
                    .getInstallationId()
                    .orElseThrow(() -> new IllegalStateException(
                            "GITHUB_APP credential missing installationId: " + credential.getName()));
            try {
                String token = gitHubApiService.getInstallationAccessToken(installationId);
                if (token == null || token.trim().isEmpty()) {
                    throw new IllegalStateException(
                            "GitHub App token exchange returned empty token for installation: " + installationId);
                }
                return GitAuthMaterial.ofToken(token);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "GitHub App token exchange failed for installation " + installationId, e);
            }
        }

        if (type == CredentialType.PAT) {
            String token = credentialService.decryptSecret(credential);
            if (token == null || token.trim().isEmpty()) {
                throw new IllegalStateException(
                        "PAT credential decryption failed or produced empty token: " + credential.getName());
            }
            return GitAuthMaterial.ofToken(token);
        }

        throw new IllegalArgumentException("Unsupported credential type: " + type);
    }
}
