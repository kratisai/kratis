package com.kratisai.controlplane.testsupport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Computes a deterministic, branch/container-scoped identifier so multiple test suites running in
 * parallel Docker containers (or parallel host git worktrees, even on a shared Docker daemon or
 * shared {@code /tmp}) never collide on networks, Testcontainers reuse labels, Docker cleanup
 * labels, E2E base image tags, or parser/blob temp directories.
 *
 * <p>Precedence: explicit {@code kratis.test.scope} system property / {@code KRATIS_TEST_SCOPE}
 * env var, otherwise an 8-char SHA-256 digest of {@code user.dir} plus container hostname when
 * executing inside Docker.
 */
public final class TestIsolationScope {

    private static final String SCOPE_ID = computeDefaultScopeId();

    private TestIsolationScope() {}

    /** Returns the process-wide isolation scope token (short lowercase alphanumeric/hyphen). */
    public static String scopeId() {
        return SCOPE_ID;
    }

    /** Returns a Testcontainers {@code workspace.id} label value unique per branch/container. */
    public static String workspaceScope() {
        return System.getProperty("user.dir", ".") + "@" + SCOPE_ID;
    }

    /** Returns the Docker bridge network name for Testcontainers Postgres and LiteLLM. */
    public static String networkName() {
        return "kratis-test-net-" + SCOPE_ID;
    }

    /** Returns the {@code kratis.instance.id} scoping sandbox containers, networks, and snapshots. */
    public static String instanceId() {
        return "kratis-test-" + SCOPE_ID;
    }

    /** Returns the scoped runner base image tag built from {@code build/Dockerfile.runner-base}. */
    public static String runnerImageTag() {
        return "kratis-runner-base:" + SCOPE_ID;
    }

    /** Returns the scoped E2E test image tag containing the baked dummy repository. */
    public static String e2eImageTag() {
        return "kratis-e2e-test:" + SCOPE_ID;
    }

    /** Returns the scoped Git HTTP/SSH fixture image tag. */
    public static String gitFixtureImageTag() {
        return "kratis-git-fixture:" + SCOPE_ID;
    }

    /** Returns a scope-isolated temp directory for {@code codebase-memory-mcp} SQLite caches. */
    public static String cbmCacheDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "kratis-cbm-cache-" + SCOPE_ID)
                .toString();
    }

    /** Returns a scope-isolated temp directory for local test blob storage. */
    public static String blobDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "kratis-test-blobs-" + SCOPE_ID)
                .toString();
    }

    private static String computeDefaultScopeId() {
        String explicit = System.getProperty("kratis.test.scope");
        if (explicit == null || explicit.isBlank()) {
            explicit = System.getenv("KRATIS_TEST_SCOPE");
        }
        boolean insideDocker = Files.exists(Path.of("/.dockerenv"));
        String userDir = System.getProperty("user.dir", ".");
        String host = resolveContainerHostname();
        return resolveScopeId(explicit, userDir, insideDocker, host);
    }

    static String resolveScopeId(
            String explicitScope, String userDir, boolean insideContainer, String containerHostname) {
        if (explicitScope != null && !explicitScope.isBlank()) {
            String sanitized = explicitScope
                    .trim()
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9-]", "-")
                    .replaceAll("^-+|-+$", "");
            if (!sanitized.isEmpty()) {
                return sanitized.length() > 24 ? sanitized.substring(0, 24) : sanitized;
            }
        }
        String material = insideContainer && containerHostname != null && !containerHostname.isBlank()
                ? userDir + "@" + containerHostname
                : userDir;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String resolveContainerHostname() {
        String envHost = System.getenv("HOSTNAME");
        if (envHost != null && !envHost.isBlank()) {
            return envHost.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "";
        }
    }
}
