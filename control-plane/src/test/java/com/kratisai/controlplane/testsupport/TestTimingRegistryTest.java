package com.kratisai.controlplane.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class TestTimingRegistryTest {

    private static final long MS = 1_000_000L;

    @Test
    void computesConcurrencySingleThreadTailAndWorkerUtilization() {
        TestTimingRegistry registry = TestTimingRegistry.createForTest();
        long t0 = 10_000_000_000L;
        registry.markSuiteStartedAt(t0);

        // Worker-1 runs SampleFastA [0..1000ms] then SampleSlowTail [1000..4000ms]
        // Worker-2 runs SampleFastB [0..1000ms], then sits idle for 3000ms
        registry.recordClassStarted(SampleFastA.class, "worker-1", t0);
        registry.recordClassStarted(SampleFastB.class, "worker-2", t0);
        registry.recordClassFinished(SampleFastA.class, false, t0 + 1000 * MS);
        registry.recordClassFinished(SampleFastB.class, false, t0 + 1000 * MS);

        registry.recordClassStarted(SampleSlowTail.class, "worker-1", t0 + 1000 * MS);
        registry.recordMethodExecution(SampleSlowTail.class, "slowMethod", 2500 * MS, false);
        registry.recordClassPhases(SampleSlowTail.class, 100 * MS, 200 * MS, 100 * MS, 2500 * MS, 100 * MS, 0L);
        registry.recordClassFinished(SampleSlowTail.class, false, t0 + 4000 * MS);
        registry.markSuiteFinishedAt(t0 + 4000 * MS);

        TestTimingRegistry.ReportSnapshot snapshot = registry.buildSnapshot();

        assertThat(snapshot.suiteWallNanos()).isEqualTo(4000 * MS);
        assertThat(snapshot.concurrency().peakConcurrency()).isEqualTo(2);
        assertThat(snapshot.concurrency().nanosByConcurrency().get(2)).isEqualTo(1000 * MS);
        assertThat(snapshot.concurrency().nanosByConcurrency().get(1)).isEqualTo(3000 * MS);
        assertThat(snapshot.concurrency().tailStartOffsetNanos()).isEqualTo(1000 * MS);
        assertThat(snapshot.concurrency().tailNanos()).isEqualTo(3000 * MS);
        assertThat(snapshot.concurrency().tailClasses()).hasSize(1);
        assertThat(snapshot.concurrency().tailClasses().getFirst()).contains("SampleSlowTail");

        assertThat(snapshot.workers()).hasSize(2);
        var w1 = snapshot.workers().getFirst();
        assertThat(w1.threadName()).isEqualTo("worker-1");
        assertThat(w1.busyNanos()).isEqualTo(4000 * MS);
        assertThat(w1.tailIdleNanos()).isZero();
        var w2 = snapshot.workers().get(1);
        assertThat(w2.threadName()).isEqualTo("worker-2");
        assertThat(w2.busyNanos()).isEqualTo(1000 * MS);
        assertThat(w2.tailIdleNanos()).isEqualTo(3000 * MS);

        String formatted = registry.formatSummaryReport(snapshot);
        assertThat(formatted)
                .contains("Single-Thread Tail")
                .contains("SampleSlowTail")
                .contains("worker-1")
                .contains("worker-2");
    }

    @Test
    void tracksContextMissesHitsDifferentiatorsAndEarlyClosure() {
        TestTimingRegistry registry = TestTimingRegistry.createForTest();
        long t0 = 1_000_000_000L;
        registry.markSuiteStartedAt(t0);

        try (AnnotationConfigApplicationContext ctx1 = new AnnotationConfigApplicationContext();
                AnnotationConfigApplicationContext ctx2 = new AnnotationConfigApplicationContext()) {

            registry.registerContextInit(ctx1, "test_aaaa1111", 15 * MS, t0 + 100 * MS);
            registry.recordContextCustomizer(ctx1, 0, "HASH", SampleFastA.class, List.of());
            registry.recordBeanInitDuration(ctx1, "liquibase", 400 * MS);
            registry.recordBeanInitDuration(ctx1, "entityManagerFactory", 250 * MS);
            registry.recordContextRefreshed(ctx1, t0 + 900 * MS);
            registry.recordClassContextUsage(SampleFastA.class, ctx1, false, 800 * MS);
            registry.recordClassContextUsage(SampleFastB.class, ctx1, true, 1 * MS);

            List<String> diffs = TestTimingRegistry.inspectDifferentiators(
                    SampleForkedClass.class,
                    Set.of(SampleForkedClass.class, ExtraConfig.class),
                    List.of("foo=bar"),
                    List.of(),
                    new String[] {"test"},
                    Set.of());
            registry.registerContextInit(ctx2, "test_bbbb2222", 12 * MS, t0 + 1000 * MS);
            registry.recordContextCustomizer(ctx2, 0, "HASH", SampleForkedClass.class, diffs);
            registry.recordContextRefreshed(ctx2, t0 + 1800 * MS);
            registry.recordClassContextUsage(SampleForkedClass.class, ctx2, false, 800 * MS);
            registry.recordContextClosed(ctx2, t0 + 2000 * MS);

            registry.markSuiteFinishedAt(t0 + 2500 * MS);

            assertThat(registry.getContextRefreshCount()).isEqualTo(2);
            assertThat(registry.getContextCacheHitCount()).isEqualTo(1);
            assertThat(registry.getTotalContextRefreshNanos()).isEqualTo(1600 * MS);
            assertThat(diffs)
                    .anyMatch(d -> d.contains("@MockitoBean(String)"))
                    .anyMatch(d -> d.contains("@TestPropertySource(foo=bar)"))
                    .anyMatch(d -> d.contains("@Import(ExtraConfig)"));

            TestTimingRegistry.ReportSnapshot snapshot = registry.buildSnapshot();
            String report = registry.formatSummaryReport(snapshot);
            assertThat(report)
                    .contains("Contexts Created (Misses): 2 | Cache Hits: 1")
                    .contains("SLOT_0 created 2 contexts")
                    .contains("Early Closures/Evictions: 1");
        }
    }

    @Test
    void categorizesSubprocessCommandsAndFormatsSummary() {
        assertThat(TestTimingRegistry.categorizeCommand(List.of("docker", "build", "-t", "img", ".")))
                .isEqualTo("docker build");
        assertThat(TestTimingRegistry.categorizeCommand(List.of("/usr/local/bin/codebase-memory-mcp", "--index")))
                .isEqualTo("codebase-memory-mcp");
        assertThat(TestTimingRegistry.categorizeCommand(List.of())).isEqualTo("unknown");

        TestTimingRegistry registry = TestTimingRegistry.createForTest();
        long t0 = 5_000_000_000L;
        registry.markSuiteStartedAt(t0);
        registry.recordClassStarted(SampleFastA.class, "worker-1", t0);
        registry.recordDbClean(50 * MS, 10 * MS, 5 * MS, 35 * MS, true);
        registry.recordDrainExecutors(20 * MS, true, List.of("agentTaskExecutor"));
        registry.recordProcessExec(List.of("docker", "commit", "c1", "img"), 300 * MS, 0);
        registry.recordSubPhase(SampleFastA.class, "sleepSnapshot", 320 * MS);
        registry.recordClassFinished(SampleFastA.class, false, t0 + 500 * MS);
        registry.markSuiteFinishedAt(t0 + 500 * MS);

        String report = registry.formatSummaryReport(registry.buildSnapshot());
        assertThat(report)
                .contains("lockHeals=1")
                .contains("agentTaskExecutor=1")
                .contains("docker commit")
                .contains("sleepSnapshot=0.32s");
    }

    private static class SampleFastA {}

    private static class SampleFastB {}

    @SlowTest(slotGroup = "E2E_DOCKER_CONTAINER")
    private static class SampleSlowTail {}

    @Configuration
    private static class ExtraConfig {}

    @Import(ExtraConfig.class)
    @TestPropertySource(properties = "foo=bar")
    private static class SampleForkedClass {
        @MockitoBean
        private String mockedDependency;
    }
}
