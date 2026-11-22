package com.kratisai.controlplane.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestDockerCleanupListenerTest {

    @Test
    void parsesDockerCliAndIsoTimestamps() {
        Instant gmt = TestDockerCleanupListener.parseDockerTimestamp("2026-11-20 10:15:30 +0000 GMT");
        assertThat(gmt).isEqualTo(Instant.parse("2026-11-20T10:15:30Z"));

        Instant tz = TestDockerCleanupListener.parseDockerTimestamp("2026-11-20 11:15:30 +0100 CET");
        assertThat(tz).isEqualTo(Instant.parse("2026-11-20T10:15:30Z"));

        Instant iso = TestDockerCleanupListener.parseDockerTimestamp("2026-11-20T10:15:30.123456Z");
        assertThat(iso).isEqualTo(Instant.parse("2026-11-20T10:15:30.123456Z"));

        assertThat(TestDockerCleanupListener.parseDockerTimestamp("")).isNull();
        assertThat(TestDockerCleanupListener.parseDockerTimestamp("invalid")).isNull();
    }

    @Test
    void onlyMarksKratisTestInstanceResourcesOlderThanMaxAgeAsStale() {
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        Duration maxAge = Duration.ofHours(48);

        assertThat(TestDockerCleanupListener.isStaleTestInstanceResource(
                        "kratis-test-ce082119", now.minus(Duration.ofHours(49)), now, maxAge))
                .isTrue();
        assertThat(TestDockerCleanupListener.isStaleTestInstanceResource(
                        "kratis-test", now.minus(Duration.ofHours(72)), now, maxAge))
                .isTrue();
        assertThat(TestDockerCleanupListener.isStaleTestInstanceResource(
                        "kratis-test-ce082119", now.minus(Duration.ofHours(12)), now, maxAge))
                .isFalse();

        // Dev / production snapshots and containers must NEVER be marked stale regardless of age
        assertThat(TestDockerCleanupListener.isStaleTestInstanceResource(
                        "kratis-dev", now.minus(Duration.ofDays(30)), now, maxAge))
                .isFalse();
        assertThat(TestDockerCleanupListener.isStaleTestInstanceResource(
                        "prod-eu-1", now.minus(Duration.ofDays(30)), now, maxAge))
                .isFalse();
        assertThat(TestDockerCleanupListener.isStaleTestInstanceResource(
                        "", now.minus(Duration.ofDays(30)), now, maxAge))
                .isFalse();
        assertThat(TestDockerCleanupListener.isStaleTestInstanceResource(
                        null, now.minus(Duration.ofDays(30)), now, maxAge))
                .isFalse();
    }

    @Test
    void prunesOnlyStaleTestTempDirectoriesOutsideActiveScope(@TempDir Path tempRoot) throws Exception {
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        FileTime oldTime = FileTime.from(now.minus(Duration.ofHours(72)));
        FileTime recentTime = FileTime.from(now.minus(Duration.ofHours(2)));

        Path staleCbm = Files.createDirectories(tempRoot.resolve("kratis-cbm-cache-deadbeef"));
        Files.writeString(staleCbm.resolve("stale.db"), "x");
        Files.setLastModifiedTime(staleCbm, oldTime);

        Path activeProtected = Files.createDirectories(tempRoot.resolve("kratis-cbm-cache-active01"));
        Files.setLastModifiedTime(activeProtected, oldTime);

        Path recentDummy = Files.createDirectories(tempRoot.resolve("kratis-dummy-repo-123"));
        Files.setLastModifiedTime(recentDummy, recentTime);

        Path unrelatedOld = Files.createDirectories(tempRoot.resolve("other-app-cache"));
        Files.setLastModifiedTime(unrelatedOld, oldTime);

        int deleted = TestDockerCleanupListener.pruneStaleTempDirectories(
                tempRoot, Set.of(activeProtected.toAbsolutePath().normalize()), now, Duration.ofHours(48));

        assertThat(deleted).isEqualTo(1);
        assertThat(staleCbm).doesNotExist();
        assertThat(activeProtected).exists();
        assertThat(recentDummy).exists();
        assertThat(unrelatedOld).exists();
    }
}
