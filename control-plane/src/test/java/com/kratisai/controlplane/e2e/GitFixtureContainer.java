package com.kratisai.controlplane.e2e;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/**
 * Testcontainers fixture: a real git server serving one repository over both
 * HTTP (Apache smart HTTP behind Basic auth) and SSH (OpenSSH, key-authenticated).
 * <p>
 * The image itself is build-cachable defined by {@code src/test/resources/fixtures/git}
 * build-cacheable. Per-run artifacts — the repository, the SSH keypair and
 * the Apache htpasswd — are generated at start time and copied into the
 * running container.
 */
// Testcontainers containers use identity equality; no value-based equals needed.
@SuppressFBWarnings("EQ_DOESNT_OVERRIDE_EQUALS")
public class GitFixtureContainer extends GenericContainer<GitFixtureContainer> {

    private static final Logger logger = LoggerFactory.getLogger(GitFixtureContainer.class);

    private static final String IMAGE_NAME = "kratis-git-fixture:static";
    private static final String HTTP_USER = "oauth2";
    private static final String SSH_USER = "gituser";
    private static final String FIXTURE_FILE_CONTENT = "Hello over HTTPS";

    private final String token;
    private Path sourceRepoDir;
    private Path fixtureDir;
    private String expectedCommitHash;
    private String sshPrivateKey;
    private String gitHttpUrl;
    private String gitSshUrl;

    public GitFixtureContainer(String token) {
        super(new ImageFromDockerfile(IMAGE_NAME, false)
                .withFileFromClasspath("Dockerfile", "fixtures/git/Dockerfile")
                .withFileFromClasspath("httpd-git.conf", "fixtures/git/httpd-git.conf"));
        this.token = token;
        withExposedPorts(80, 2222);
    }

    @Override
    public void start() {
        try {
            prepareFixtureFiles();
            super.start();
            injectFixtureFiles();
            // Consumed by sandbox containers. They reach this fixture through the docker host its
            // published ports live on: TESTCONTAINERS_HOST_OVERRIDE when this JVM runs inside a
            // sandbox (the sibling sharing their network), otherwise host.docker.internal.
            String fixtureHost = Optional.ofNullable(System.getenv("TESTCONTAINERS_HOST_OVERRIDE"))
                    .orElse("host.docker.internal");
            gitHttpUrl = "http://" + fixtureHost + ":" + getMappedPort(80) + "/git/repo.git";
            gitSshUrl = "ssh://" + SSH_USER + "@" + fixtureHost + ":" + getMappedPort(2222) + "/srv/git/repo.git";
            logger.info("[git-fixture] serving {} and {}", gitHttpUrl, gitSshUrl);
        } catch (IOException | InterruptedException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to start git fixture container", e);
        }
    }

    @Override
    public void stop() {
        super.stop();
        deleteRecursively(fixtureDir);
        deleteRecursively(sourceRepoDir);
    }

    /** HTTP(S) clone URL for the fixture repository, reachable from sandbox containers. */
    public String getGitHttpUrl() {
        return gitHttpUrl;
    }

    /** SSH clone URL for the fixture repository, reachable from sandbox containers. */
    public String getGitSshUrl() {
        return gitSshUrl;
    }

    /** HEAD of the fixture repository, for verifying the checkout. */
    public String getExpectedCommitHash() {
        return expectedCommitHash;
    }

    /** Generated ed25519 private key that authenticates the SSH user. */
    public String getSshPrivateKey() {
        return sshPrivateKey;
    }

    private void prepareFixtureFiles() throws IOException, InterruptedException, NoSuchAlgorithmException {
        sourceRepoDir = Files.createTempDirectory("kratis-git-source-");
        runCommand(sourceRepoDir, "git", "init", "-b", "main");
        Files.writeString(sourceRepoDir.resolve("hello.txt"), FIXTURE_FILE_CONTENT);
        runCommand(sourceRepoDir, "git", "add", ".");
        runCommand(
                sourceRepoDir,
                "git",
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.com",
                "commit",
                "-m",
                "Initial");
        expectedCommitHash = runCommand(sourceRepoDir, "git", "rev-parse", "HEAD");

        fixtureDir = Files.createTempDirectory("kratis-git-fixture-");
        runCommand(fixtureDir, "git", "clone", "--bare", sourceRepoDir.toString(), "repo.git");

        Path keyPath = fixtureDir.resolve("id_ed25519");
        runCommand(fixtureDir, "ssh-keygen", "-t", "ed25519", "-N", "", "-f", keyPath.toString());
        sshPrivateKey = Files.readString(keyPath);
        Files.writeString(
                fixtureDir.resolve("authorized_keys"),
                Files.readString(fixtureDir.resolve("id_ed25519.pub")).trim() + "\n");

        String sha1 = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-1").digest(token.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(fixtureDir.resolve("htpasswd"), HTTP_USER + ":{SHA}" + sha1 + "\n");
    }

    private void injectFixtureFiles() throws IOException, InterruptedException {
        copyFileToContainer(MountableFile.forHostPath(fixtureDir.resolve("repo.git")), "/srv/git/repo.git");
        copyFileToContainer(
                MountableFile.forHostPath(fixtureDir.resolve("authorized_keys")), "/home/gituser/.ssh/authorized_keys");
        copyFileToContainer(MountableFile.forHostPath(fixtureDir.resolve("htpasswd")), "/etc/apache2/htpasswd");

        runInContainer("chown", "-R", SSH_USER + ":" + SSH_USER, "/home/gituser/.ssh", "/srv/git/repo.git");
        runInContainer("chmod", "700", "/home/gituser/.ssh");
        runInContainer("chmod", "600", "/home/gituser/.ssh/authorized_keys");
        runInContainer("chmod", "-R", "a+rX", "/srv/git");
    }

    private void runInContainer(String... command) throws IOException, InterruptedException {
        ExecResult result = execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Container command failed: " + String.join(" ", command) + "\n"
                    + result.getStdout() + result.getStderr());
        }
    }

    private static String runCommand(Path dir, String... args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("Command failed (" + code + "): " + String.join(" ", args) + "\n" + output);
        }
        return output.trim();
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // ignore
                }
            });
        } catch (IOException e) {
            // ignore
        }
    }
}
