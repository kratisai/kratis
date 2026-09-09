package com.kratisai.controlplane.git.provider;

import com.kratisai.controlplane.api.restdto.PullRequestResultDto;
import com.kratisai.controlplane.api.restdto.RemoteRepositoryDto;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.credential.SshKeyFile;
import com.kratisai.controlplane.model.RepoCredential;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Fallback provider for repositories with no host REST API: SSH-key authenticated repos (any
 * provider) and {@link RepositoryType#GENERIC} repos. Streams a single path via
 * {@code git archive --remote=<url> <ref>:<path>}, injecting the SSH key through
 * {@code GIT_SSH_COMMAND}. Repository listing is impossible over the raw git protocol.
 */
@Component
public class GitProtocolRepoProvider implements RepoProvider {

    private static final int MAX_FILE_SIZE_BYTES = 1_000_000;

    private final GitCommandRunner commandRunner;

    public GitProtocolRepoProvider(GitCommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    @Override
    public RepositoryType supportedType() {
        return RepositoryType.GENERIC;
    }

    @Override
    public String readFile(Repository repo, GitAuthMaterial auth, String path, String branch) {
        String url = Objects.requireNonNull(repo.getUrl(), "repo.url must not be null");
        String ref = branch != null && !branch.isBlank() ? branch : "HEAD";
        String archiveSpec = ref + ":" + path;

        Map<String, String> environment = new HashMap<>();
        SshKeyFile keyFile = null;
        if (auth != null && auth.maybeSshKey().isPresent()) {
            keyFile = writeSshKey(auth.maybeSshKey().get());
            environment.put(
                    "GIT_SSH_COMMAND", "ssh -i " + keyFile.path().toAbsolutePath() + " -o StrictHostKeyChecking=no");
        }

        try (SshKeyFile ignored = keyFile) {
            GitCommandRunner.Result result = commandRunner.run(
                    List.of("git", "archive", "--remote=" + url, archiveSpec),
                    environment.isEmpty() ? null : environment);
            byte[] output = result.output();
            if (result.exitCode() != 0) {
                throw new IllegalStateException("git archive failed for " + url + " at " + ref + ":" + path + " (exit "
                        + result.exitCode() + "): "
                        + new String(output, StandardCharsets.UTF_8));
            }
            if (output.length > MAX_FILE_SIZE_BYTES) {
                throw new IllegalStateException(
                        "File too large (" + output.length + " bytes). Maximum is " + MAX_FILE_SIZE_BYTES);
            }
            return new String(output, StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to read file via git protocol: " + e.getMessage(), e);
        }
    }

    private SshKeyFile writeSshKey(String pem) {
        try {
            return SshKeyFile.write(pem);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write SSH key file", e);
        }
    }

    @Override
    public List<RemoteRepositoryDto> listRepositories(RepoCredential credential, GitAuthMaterial auth) {
        throw new UnsupportedOperationException("The git protocol does not support listing a remote's repositories");
    }

    @Override
    public boolean supportsPullRequests() {
        return false;
    }

    @Override
    public PullRequestResultDto createPullRequest(
            Repository repo, GitAuthMaterial auth, CreatePullRequestCommand command) {
        throw new UnsupportedOperationException(
                "Generic Git / SSH repositories do not support upstream Pull Request creation via REST API");
    }
}
