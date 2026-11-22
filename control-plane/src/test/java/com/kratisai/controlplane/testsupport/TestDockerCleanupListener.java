package com.kratisai.controlplane.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes Docker resources left behind by integration tests that exercise the docker host with
 * this workspace/container's scoped {@code kratis.instance.id}, and prunes stale test-labeled
 * resources ({@code kratis.test.resource=true} or {@code kratis.instance.id} starting with
 * {@code kratis-test}) older than 48 hours.
 *
 * <p>Developer stacks and sleeping non-test {@code kratis-snapshot-*} images are never matched and
 * never touched. Best-effort: without a docker CLI the e2e tests already fail on their own.
 */
public class TestDockerCleanupListener implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(TestDockerCleanupListener.class);
    private static final String TEST_INSTANCE_ID = TestIsolationScope.instanceId();
    private static final String TEST_INSTANCE_PREFIX = "kratis-test";
    private static final String TEST_RESOURCE_LABEL = "kratis.test.resource=true";
    private static final Duration STALE_RESOURCE_AGE = Duration.ofHours(48);
    private static final String STALE_UNTIL_FILTER = "until=48h";
    private static final DateTimeFormatter DOCKER_CLI_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
    private static final List<String> STALE_TEMP_PREFIXES =
            List.of("kratis-cbm-cache-", "kratis-test-blobs-", "kratis-dummy-repo-", "kratis-repo-");

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        long start = System.nanoTime();
        cleanCurrentScope();
        pruneStaleTestResources(Instant.now());
        TestTimingRegistry.instance().recordDockerCleanup(true, System.nanoTime() - start);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        long start = System.nanoTime();
        cleanCurrentScope();
        TestTimingRegistry.instance().recordDockerCleanup(false, System.nanoTime() - start);
    }

    private static void cleanCurrentScope() {
        remove("container", List.of("docker", "ps", "-aq", "--filter", "label=kratis.instance.id=" + TEST_INSTANCE_ID));
        remove(
                "network",
                List.of("docker", "network", "ls", "-q", "--filter", "label=kratis.instance.id=" + TEST_INSTANCE_ID));
        remove(
                "image",
                List.of(
                        "docker",
                        "images",
                        "-q",
                        "--filter",
                        "reference=kratis-snapshot-*:latest",
                        "--filter",
                        "label=kratis.instance.id=" + TEST_INSTANCE_ID));
    }

    private static void pruneStaleTestResources(Instant now) {
        // 1. Native Docker prune for test-only images, networks, and stopped containers >48h
        runQuiet(List.of(
                "docker",
                "container",
                "prune",
                "-f",
                "--filter",
                STALE_UNTIL_FILTER,
                "--filter",
                "label=" + TEST_RESOURCE_LABEL));
        runQuiet(List.of(
                "docker",
                "network",
                "prune",
                "-f",
                "--filter",
                STALE_UNTIL_FILTER,
                "--filter",
                "label=" + TEST_RESOURCE_LABEL));
        runQuiet(List.of(
                "docker",
                "image",
                "prune",
                "-af",
                "--filter",
                STALE_UNTIL_FILTER,
                "--filter",
                "label=" + TEST_RESOURCE_LABEL));

        // 2. Cross-scope orphaned kratis-test* resources (containers, networks, crashed snapshots) >48h
        pruneStaleTestContainers(now);
        pruneStaleTestNetworks(now);
        pruneStaleTestSnapshots(now);

        // 3. Stale host temp directories (>48h) outside the current isolation scope
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> activeScopeDirs = Set.of(
                Path.of(TestIsolationScope.cbmCacheDir()).toAbsolutePath().normalize(),
                Path.of(TestIsolationScope.blobDir()).toAbsolutePath().normalize());
        pruneStaleTempDirectories(tmpDir, activeScopeDirs, now, STALE_RESOURCE_AGE);
    }

    private static void pruneStaleTestContainers(Instant now) {
        String output = runQuiet(List.of(
                "docker",
                "ps",
                "-a",
                "--filter",
                "label=kratis.instance.id",
                "--format",
                "{{.ID}}\t{{.Label \"kratis.instance.id\"}}\t{{.CreatedAt}}"));
        if (output == null) {
            return;
        }
        int removed = 0;
        for (String line : output.lines().toList()) {
            String[] parts = line.split("\t", 3);
            if (parts.length == 3
                    && isStaleTestInstanceResource(parts[1], parseDockerTimestamp(parts[2]), now, STALE_RESOURCE_AGE)) {
                if (runQuiet(List.of("docker", "rm", "-f", "-v", parts[0])) != null) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("Pruned {} stale (>48h) test container(s)", removed);
        }
    }

    private static void pruneStaleTestNetworks(Instant now) {
        String output = runQuiet(List.of(
                "docker",
                "network",
                "ls",
                "--filter",
                "label=kratis.instance.id",
                "--format",
                "{{.ID}}\t{{.Label \"kratis.instance.id\"}}\t{{.CreatedAt}}"));
        if (output == null) {
            return;
        }
        int removed = 0;
        for (String line : output.lines().toList()) {
            String[] parts = line.split("\t", 3);
            if (parts.length == 3
                    && isStaleTestInstanceResource(parts[1], parseDockerTimestamp(parts[2]), now, STALE_RESOURCE_AGE)) {
                if (runQuiet(List.of("docker", "network", "rm", parts[0])) != null) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("Pruned {} stale (>48h) test network(s)", removed);
        }
    }

    private static void pruneStaleTestSnapshots(Instant now) {
        String output = runQuiet(List.of(
                "docker",
                "images",
                "--filter",
                "reference=kratis-snapshot-*:latest",
                "--format",
                "{{.ID}}\t{{.CreatedAt}}"));
        if (output == null) {
            return;
        }
        int removed = 0;
        for (String line : output.lines().toList()) {
            String[] parts = line.split("\t", 2);
            if (parts.length != 2) {
                continue;
            }
            Instant created = parseDockerTimestamp(parts[1]);
            if (created == null || !created.isBefore(now.minus(STALE_RESOURCE_AGE))) {
                continue;
            }
            String instanceId = runQuiet(List.of(
                    "docker", "inspect", "--format", "{{index .Config.Labels \"kratis.instance.id\"}}", parts[0]));
            if (instanceId != null
                    && isStaleTestInstanceResource(instanceId.trim(), created, now, STALE_RESOURCE_AGE)) {
                if (runQuiet(List.of("docker", "rmi", parts[0])) != null) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("Pruned {} stale (>48h) orphaned test snapshot image(s)", removed);
        }
    }

    static int pruneStaleTempDirectories(Path tempRoot, Set<Path> activeProtectedDirs, Instant now, Duration maxAge) {
        if (tempRoot == null || !Files.isDirectory(tempRoot)) {
            return 0;
        }
        Instant cutoff = now.minus(maxAge);
        int deleted = 0;
        try (Stream<Path> entries = Files.list(tempRoot)) {
            for (Path entry : entries.toList()) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                Path fileName = entry.getFileName();
                String name = fileName != null ? fileName.toString() : "";
                boolean isTestTempDir = STALE_TEMP_PREFIXES.stream().anyMatch(name::startsWith);
                if (!isTestTempDir) {
                    continue;
                }
                Path normalized = entry.toAbsolutePath().normalize();
                if (activeProtectedDirs != null && activeProtectedDirs.contains(normalized)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
                        deleteDirectoryRecursively(entry);
                        deleted++;
                    }
                } catch (IOException ignored) {
                    // Best effort cleanup of orphaned temp directories
                }
            }
        } catch (IOException e) {
            log.debug("Skipping temp directory prune under {}: {}", tempRoot, e.getMessage());
        }
        if (deleted > 0) {
            log.info("Pruned {} stale (>48h) test temp directory(ies)", deleted);
        }
        return deleted;
    }

    static boolean isStaleTestInstanceResource(String instanceId, Instant createdAt, Instant now, Duration maxAge) {
        if (instanceId == null || !instanceId.startsWith(TEST_INSTANCE_PREFIX) || createdAt == null) {
            return false;
        }
        return createdAt.isBefore(now.minus(maxAge));
    }

    static Instant parseDockerTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // Try OffsetDateTime / Docker CLI format next
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try Docker CLI "yyyy-MM-dd HH:mm:ss Z zzz"
        }
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length >= 3) {
            String withoutZoneName = tokens[0] + " " + tokens[1] + " " + tokens[2];
            try {
                return OffsetDateTime.parse(withoutZoneName, DOCKER_CLI_DATE_FORMAT)
                        .toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void remove(String kind, List<String> listCommand) {
        List<String> ids;
        try {
            ids = run(listCommand).lines().filter(line -> !line.isBlank()).toList();
        } catch (IOException | InterruptedException e) {
            log.debug("Skipping test docker cleanup ({}): {}", kind, e.getMessage());
            return;
        }
        for (String id : ids) {
            try {
                switch (kind) {
                    case "container" -> run(List.of("docker", "rm", "-f", "-v", id));
                    case "network" -> run(List.of("docker", "network", "rm", id));
                    default -> run(List.of("docker", "rmi", "-f", id));
                }
            } catch (IOException | InterruptedException e) {
                log.warn("Failed to remove leftover test {} {}: {}", kind, id, e.getMessage());
            }
        }
        if (!ids.isEmpty()) {
            log.info("Removed {} leftover test {}(s)", ids.size(), kind);
        }
    }

    private static String runQuiet(List<String> command) {
        try {
            return run(command);
        } catch (IOException | InterruptedException e) {
            log.debug("Skipping command {}: {}", String.join(" ", command), e.getMessage());
            return null;
        }
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("exit " + process.exitValue() + ": " + String.join(" ", command));
        }
        return output;
    }
}
