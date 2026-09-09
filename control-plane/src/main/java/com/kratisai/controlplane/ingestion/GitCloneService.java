package com.kratisai.controlplane.ingestion;

import com.kratisai.controlplane.git.GitTransportUrlBuilder;
import com.kratisai.controlplane.git.credential.GitAuthMaterial;
import com.kratisai.controlplane.git.credential.SshKeyFile;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.service.GitCredentialResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GitCloneService {

    private static final Logger logger = LoggerFactory.getLogger(GitCloneService.class);

    private final String baseCloneDir;
    private final GitCredentialResolver credentialResolver;
    private final GitTransportUrlBuilder transportUrlBuilder;

    public GitCloneService(
            @Value("${kratis.ingestion.clone-dir:${java.io.tmpdir}/kratis-ingest}") String baseCloneDir,
            GitCredentialResolver credentialResolver,
            GitTransportUrlBuilder transportUrlBuilder) {
        this.baseCloneDir = baseCloneDir;
        this.credentialResolver = credentialResolver;
        this.transportUrlBuilder = transportUrlBuilder;
    }

    public record CloneResult(Path cloneDirectory, String commitHash) {}

    public CloneResult cloneRepository(
            Repository repository, UUID batchId, IngestionBatchLogService.BatchLogger batchLogger)
            throws IOException, InterruptedException {
        Path destination = Path.of(baseCloneDir, batchId.toString());

        // Clean up existing directory if it exists (e.g., from a previous failed or interrupted run)
        if (Files.exists(destination)) {
            logger.info("Cleaning up existing directory before cloning: {}", destination);
            cleanupDirectory(destination);
        }

        // Ensure parent directories exist
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String rawUrl = repository.getUrl();
        String branch = repository.getBranch() != null ? repository.getBranch() : "main";

        // Resolve auth via the shared resolver (handles PAT / GITHUB_APP / SSH_KEY / public repo)
        GitAuthMaterial auth = credentialResolver.resolve(repository);

        // Build the provider-aware authenticated clone URL (no-op for SSH_KEY / public repos)
        String cloneUrl = transportUrlBuilder.buildAuthenticatedCloneUrl(rawUrl, repository.getRepositoryType(), auth);

        String scrubbedUrl =
                auth.maybeToken().map(token -> cloneUrl.replace(token, "***")).orElse(cloneUrl);

        logger.info(
                "Starting git clone for repository {} (branch: {}) to {}",
                repository.getName(),
                branch,
                destination.toAbsolutePath());
        logger.debug("Executing: git clone --depth 1 --branch {} {} {}", branch, scrubbedUrl, destination);

        List<String> command = List.of(
                "git",
                "clone",
                "--depth",
                "1",
                "--branch",
                branch,
                cloneUrl,
                destination.toAbsolutePath().toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().put("GIT_TERMINAL_PROMPT", "0");

        // Materialize an ephemeral SSH key file when the credential is an SSH key.
        // Managed by try-with-resources below for guaranteed cleanup.
        final SshKeyFile sshKeyFile = materializeSshKey(auth);
        if (sshKeyFile != null) {
            processBuilder
                    .environment()
                    .put(
                            "GIT_SSH_COMMAND",
                            "ssh -i " + sshKeyFile.path().toAbsolutePath() + " -o StrictHostKeyChecking=no");
        }

        try (sshKeyFile) {
            Process process = processBuilder.start();

            // Read streams asynchronously (virtual-thread safe blocking reads)
            byte[] errorBytes = process.getErrorStream().readAllBytes();
            String errorOutput = new String(errorBytes, StandardCharsets.UTF_8);

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String tokenToScrub = auth.maybeToken().orElse("SECRET_TOKEN_NOT_SET");
                String cleanError = errorOutput.replace(tokenToScrub, "***");
                logger.error("Git clone failed with exit code {}. Error output: {}", exitCode, cleanError);
                // Cleanup directory if it was partially created
                try {
                    cleanupDirectory(destination);
                } catch (Exception ex) {
                    logger.warn("Failed to cleanup directory {} after clone failure", destination, ex);
                }
                throw new IOException("Git clone failed: " + cleanError);
            }

            logger.info("Git clone completed successfully for repository {}", repository.getName());

            // Resolve commit hash
            String commitHash = resolveCommitHash(destination);
            batchLogger.info("CLONE", "Cloned at Commit hash: " + commitHash);
            return new CloneResult(destination, commitHash);
        }
    }

    private SshKeyFile materializeSshKey(GitAuthMaterial auth) throws IOException {
        if (auth.maybeSshKey().isEmpty()) {
            return null;
        }
        return SshKeyFile.write(auth.maybeSshKey().get());
    }

    private String resolveCommitHash(Path repositoryDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(repositoryDir.toFile());
            Process p = pb.start();

            byte[] outputBytes = p.getInputStream().readAllBytes();
            int exitCode = p.waitFor();

            if (exitCode == 0) {
                return new String(outputBytes, StandardCharsets.UTF_8).trim();
            } else {
                logger.warn("git rev-parse HEAD failed with exit code {}", exitCode);
                return "UNKNOWN";
            }
        } catch (Exception e) {
            logger.warn("Failed to resolve commit hash via git rev-parse HEAD", e);
            return "UNKNOWN";
        }
    }

    public void cleanupDirectory(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()) // delete children first
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            logger.warn("Failed to delete path {}", p, e);
                        }
                    });
        }
    }
}
