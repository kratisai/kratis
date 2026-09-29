package com.kratisai.controlplane;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global JUnit 5 extension that records timing statistics for every test
 * execution. Auto-registered via
 * META-INF/services/org.junit.jupiter.api.extension.Extension.
 * <p>
 * Captures:
 * <ul>
 * <li>Per-test method execution time (wall clock)</li>
 * <li>Per-test class total time (first beforeAll → last afterAll)</li>
 * <li>@BeforeEach / @AfterEach overhead (approximated as class time minus sum
 * of test times)</li>
 * <li>Spring context cache statistics via companion ContextCacheTracker</li>
 * <li>Number of Spring ApplicationContext refreshes</li>
 * </ul>
 * <p>
 * Prints a ranked summary to STDOUT and writes CSV to
 * target/test-timing-report.csv at JVM shutdown.
 */
public class TestExecutionTimingExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Logger log = LoggerFactory.getLogger(TestExecutionTimingExtension.class);

    // ── Global state shared across all test classes ──

    private static final Instant SUITE_START = Instant.now();

    /** Map from fully-qualified class name → class-level stats */
    private static final ConcurrentHashMap<String, ClassStats> CLASS_STATS = new ConcurrentHashMap<>();

    /** Map from "ClassName#methodName" → duration in ms */
    private static final ConcurrentHashMap<String, Long> TEST_DURATIONS = new ConcurrentHashMap<>();

    /** Ordered list recording the sequence of test class executions */
    private static final List<String> CLASS_ORDER = Collections.synchronizedList(new ArrayList<>());

    /** Total context refreshes observed (updated by {@link ContextCacheTracker}) */
    static final AtomicInteger CONTEXT_REFRESHES = new AtomicInteger(0);

    /** Context cache hit count (updated by {@link ContextCacheTracker}) */
    static final AtomicInteger CONTEXT_CACHE_HITS = new AtomicInteger(0);

    /** Total time spent in context refresh, nanoseconds */
    static final AtomicLong CONTEXT_REFRESH_NANOS = new AtomicLong(0);

    /** Map from context cache key hash → list of class names that used it */
    static final ConcurrentHashMap<String, List<String>> CONTEXT_KEY_USERS = new ConcurrentHashMap<>();

    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    static {
        registerShutdownHook();
    }

    // ── Per-class timing ──

    @Override
    public void beforeAll(ExtensionContext context) {
        String className = context.getRequiredTestClass().getSimpleName();
        ClassStats stats = CLASS_STATS.computeIfAbsent(className, k -> new ClassStats());
        stats.classStart = Instant.now();
        if (!CLASS_ORDER.contains(className)) {
            CLASS_ORDER.add(className);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String className = context.getRequiredTestClass().getSimpleName();
        ClassStats stats = CLASS_STATS.get(className);
        if (stats != null && stats.classStart != null) {
            stats.classDurationMs =
                    Duration.between(stats.classStart, Instant.now()).toMillis();
        }
    }

    // ── Per-test timing ──

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        String className = context.getRequiredTestClass().getSimpleName();
        ClassStats stats = CLASS_STATS.computeIfAbsent(className, k -> new ClassStats());
        stats.testStartNanos.set(System.nanoTime());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        String className = context.getRequiredTestClass().getSimpleName();
        String methodName = context.getRequiredTestMethod().getName();
        ClassStats stats = CLASS_STATS.get(className);

        long durationMs = 0;
        if (stats != null && stats.testStartNanos.get() > 0) {
            durationMs = (System.nanoTime() - stats.testStartNanos.get()) / 1_000_000;
            stats.testCount.incrementAndGet();
            stats.totalTestTimeMs.addAndGet(durationMs);
        }

        String key = className + "#" + methodName;
        TEST_DURATIONS.put(key, durationMs);
    }

    // ── Shutdown report ──

    private static void registerShutdownHook() {
        if (SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(TestExecutionTimingExtension::printReport, "test-timing-report"));
        }
    }

    @SuppressWarnings("java:S106") // System.out is intentional for test report
    static void printReport() {
        long suiteElapsedMs = Duration.between(SUITE_START, Instant.now()).toMillis();
        double suiteElapsedSec = suiteElapsedMs / 1000.0;

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║                    TEST EXECUTION TIMING REPORT                         ║\n");
        sb.append("╠══════════════════════════════════════════════════════════════════════════╣\n");
        sb.append(String.format(
                "║  Total suite wall-clock time: %.1fs                              ║%n", suiteElapsedSec));
        sb.append(String.format(
                "║  Spring context refreshes:    %d (cache hits: %d)                  ║%n",
                CONTEXT_REFRESHES.get(), CONTEXT_CACHE_HITS.get()));
        sb.append(String.format(
                "║  Context refresh total time:  %.1fs                                ║%n",
                CONTEXT_REFRESH_NANOS.get() / 1_000_000_000.0));
        sb.append(String.format(
                "║  Total test classes:          %d                                    ║%n", CLASS_STATS.size()));
        sb.append(String.format(
                "║  Total test methods:          %d                                    ║%n", TEST_DURATIONS.size()));
        sb.append("╚══════════════════════════════════════════════════════════════════════════╝\n\n");

        // ── Top 20 slowest test CLASSES ──
        sb.append("┌─────────────────────────────────────────────────────────────────────────┐\n");
        sb.append("│  TOP 20 SLOWEST TEST CLASSES                                            │\n");
        sb.append("├────────────────────────────────────────────┬───────┬──────────┬──────────┤\n");
        sb.append("│ Class Name                                 │ Tests │ Total(s) │ Setup(s) │\n");
        sb.append("├────────────────────────────────────────────┼───────┼──────────┼──────────┤\n");

        CLASS_STATS.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, ClassStats>>comparingLong(e -> e.getValue().classDurationMs)
                        .reversed())
                .limit(20)
                .forEach(e -> {
                    String name = e.getKey();
                    ClassStats cs = e.getValue();
                    double totalSec = cs.classDurationMs / 1000.0;
                    double testSec = cs.totalTestTimeMs.get() / 1000.0;
                    double setupSec = Math.max(0, totalSec - testSec);
                    String truncatedName = name.length() > 42 ? name.substring(0, 42) : name;
                    sb.append(String.format(
                            "│ %-42s │ %5d │ %8.1f │ %8.1f │%n",
                            truncatedName, cs.testCount.get(), totalSec, setupSec));
                });
        sb.append("└────────────────────────────────────────────┴───────┴──────────┴──────────┘\n\n");

        // ── Top 20 slowest individual TESTS ──
        sb.append("┌─────────────────────────────────────────────────────────────────────────┐\n");
        sb.append("│  TOP 20 SLOWEST INDIVIDUAL TESTS                                        │\n");
        sb.append("├────────────────────────────────────────────────────────────────┬──────────┤\n");
        sb.append("│ Test                                                           │ Time(s)  │\n");
        sb.append("├────────────────────────────────────────────────────────────────┼──────────┤\n");

        TEST_DURATIONS.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .reversed())
                .limit(20)
                .forEach(e -> {
                    String name = e.getKey();
                    String truncatedName = name.length() > 62 ? name.substring(0, 62) : name;
                    sb.append(String.format("│ %-62s │ %8.1f │%n", truncatedName, e.getValue() / 1000.0));
                });
        sb.append("└────────────────────────────────────────────────────────────────┴──────────┘\n\n");

        // ── Spring Context sharing analysis ──
        if (!CONTEXT_KEY_USERS.isEmpty()) {
            sb.append("┌─────────────────────────────────────────────────────────────────────────┐\n");
            sb.append("│  SPRING CONTEXT SHARING ANALYSIS                                        │\n");
            sb.append("├─────────────────────────────────────────────────────────────────────────┤\n");

            CONTEXT_KEY_USERS.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, List<String>>>comparingInt(
                                    e -> e.getValue().size())
                            .reversed())
                    .forEach(e -> {
                        List<String> users = e.getValue();
                        sb.append(String.format("│ Context[%s] shared by %d class(es):%n", e.getKey(), users.size()));
                        users.forEach(u -> sb.append(String.format("│   - %s%n", u)));
                    });
            sb.append("└─────────────────────────────────────────────────────────────────────────┘\n\n");
        }

        // ── @BeforeEach overhead analysis ──
        long totalSetupMs = 0;
        long totalTestMs = 0;
        for (ClassStats cs : CLASS_STATS.values()) {
            totalTestMs += cs.totalTestTimeMs.get();
            long setupMs = cs.classDurationMs - cs.totalTestTimeMs.get();
            if (setupMs > 0) {
                totalSetupMs += setupMs;
            }
        }
        double overheadPct = totalTestMs > 0 ? (totalSetupMs * 100.0 / (totalSetupMs + totalTestMs)) : 0;
        sb.append(String.format(
                "📊 Setup/teardown overhead: %.1fs (%.0f%% of class execution time)%n%n",
                totalSetupMs / 1000.0, overheadPct));

        // ── Execution order (to detect context thrashing) ──
        sb.append("┌─────────────────────────────────────────────────────────────────────────┐\n");
        sb.append("│  TEST CLASS EXECUTION ORDER (detect context thrashing)                   │\n");
        sb.append("├─────────────────────────────────────────────────────────────────────────┤\n");
        for (int i = 0; i < CLASS_ORDER.size(); i++) {
            sb.append(String.format("│ %3d. %s%n", i + 1, CLASS_ORDER.get(i)));
        }
        sb.append("└─────────────────────────────────────────────────────────────────────────┘\n");

        System.out.println(sb);

        // Write CSV for machine consumption
        writeCsv();
    }

    private static void writeCsv() {
        Path csvPath = Path.of("target/test-timing-report.csv");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(csvPath))) {
            pw.println("type,name,tests,total_ms,test_ms,setup_ms");
            CLASS_STATS.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, ClassStats>>comparingLong(e -> e.getValue().classDurationMs)
                            .reversed())
                    .forEach(e -> {
                        ClassStats cs = e.getValue();
                        long setupMs = Math.max(0, cs.classDurationMs - cs.totalTestTimeMs.get());
                        pw.printf(
                                "class,%s,%d,%d,%d,%d%n",
                                e.getKey(), cs.testCount.get(), cs.classDurationMs, cs.totalTestTimeMs.get(), setupMs);
                    });

            TEST_DURATIONS.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                            .reversed())
                    .forEach(e -> pw.printf("test,%s,1,%d,%d,0%n", e.getKey(), e.getValue(), e.getValue()));

            pw.printf(
                    "suite,TOTAL,%d,%d,0,0%n",
                    TEST_DURATIONS.size(),
                    Duration.between(SUITE_START, Instant.now()).toMillis());

            log.info("📄 Test timing CSV written to {}", csvPath.toAbsolutePath());
        } catch (IOException ex) {
            log.warn("Failed to write test timing CSV: {}", ex.getMessage());
        }
    }

    // ── Inner helper ──

    static class ClassStats {
        volatile Instant classStart;
        volatile long classDurationMs;
        final AtomicInteger testCount = new AtomicInteger(0);
        final AtomicLong totalTestTimeMs = new AtomicLong(0);
        final ThreadLocal<Long> testStartNanos = ThreadLocal.withInitial(() -> 0L);
    }
}
