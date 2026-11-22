package com.kratisai.controlplane.testsupport;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thread-safe telemetry registry for test execution timelines, Spring context caching/refreshes,
 * PostgreSQL schema provisioning, database cleaning, executor draining, subprocess execution, and
 * E2E Docker lifecycle phases.
 */
public final class TestTimingRegistry {

    private static final Logger logger = LoggerFactory.getLogger(TestTimingRegistry.class);

    public static final int SLOT_NONE = -1;
    private static final long NANOS_PER_MS = 1_000_000L;
    private static final double NANOS_PER_SEC = 1_000_000_000.0;
    private static final long DRAIN_WAIT_THRESHOLD_NANOS = 5_000_000L;
    private static final long MAX_CLASS_IDLE_DRAIN_NANOS = 2_000_000_000L;
    private static final int TOP_LIMIT = 25;

    private static final TestTimingRegistry INSTANCE = new TestTimingRegistry();

    /** Returns the process-wide timing registry used by JUnit and Spring test hooks. */
    public static TestTimingRegistry instance() {
        return INSTANCE;
    }

    /** Creates an isolated registry instance for unit tests. */
    static TestTimingRegistry createForTest() {
        return new TestTimingRegistry();
    }

    private final AtomicLong suiteStartNanos = new AtomicLong(-1L);
    private final AtomicLong suiteEndNanos = new AtomicLong(-1L);
    private final AtomicBoolean suiteFinished = new AtomicBoolean(false);
    private final AtomicBoolean reportEmitted = new AtomicBoolean(false);

    private final AtomicReference<ContainerBootstrapStats> containerBootstrap = new AtomicReference<>();
    private final LongAdder preSuiteDockerCleanupNanos = new LongAdder();
    private final LongAdder postSuiteDockerCleanupNanos = new LongAdder();

    private final ConcurrentHashMap<String, ClassTiming> classes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MethodTiming> methods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IdentityKey, ContextTrace> contexts = new ConcurrentHashMap<>();
    private final AtomicInteger contextSeq = new AtomicInteger(0);

    private final ConcurrentHashMap<String, ProcessCategoryStats> processCategories = new ConcurrentHashMap<>();
    private final LongAdder dbCleanCalls = new LongAdder();
    private final LongAdder dbCleanTotalNanos = new LongAdder();
    private final LongAdder dbCleanLitellmNanos = new LongAdder();
    private final LongAdder dbCleanDiscoveryNanos = new LongAdder();
    private final LongAdder dbCleanTruncateNanos = new LongAdder();
    private final LongAdder dbCleanLockHeals = new LongAdder();
    private final AtomicLong dbCleanMaxNanos = new AtomicLong(0L);

    private final LongAdder drainCalls = new LongAdder();
    private final LongAdder drainTotalNanos = new LongAdder();
    private final LongAdder drainIdleCalls = new LongAdder();
    private final LongAdder drainIdleNanos = new LongAdder();
    private final LongAdder drainBusyCalls = new LongAdder();
    private final LongAdder drainBusyNanos = new LongAdder();
    private final LongAdder drainWaitEvents = new LongAdder();
    private final AtomicLong drainMaxNanos = new AtomicLong(0L);
    private final ConcurrentHashMap<String, LongAdder> drainWaitedBeans = new ConcurrentHashMap<>();

    private final LongAdder chatModelResetCalls = new LongAdder();
    private final LongAdder chatModelResetTotalNanos = new LongAdder();

    private final ThreadLocal<Deque<String>> activeClassStack = ThreadLocal.withInitial(ArrayDeque::new);

    private TestTimingRegistry() {}

    /** Marks the start of the JUnit test plan if not already anchored. */
    public void markSuiteStarted() {
        markSuiteStartedAt(System.nanoTime());
    }

    void markSuiteStartedAt(long nanoTime) {
        suiteStartNanos.compareAndSet(-1L, nanoTime);
    }

    /** Marks the end of the JUnit test plan. */
    public void markSuiteFinished() {
        markSuiteFinishedAt(System.nanoTime());
    }

    void markSuiteFinishedAt(long nanoTime) {
        suiteFinished.set(true);
        suiteEndNanos.compareAndSet(-1L, nanoTime);
    }

    private long ensureSuiteStarted(long nowNanos) {
        long existing = suiteStartNanos.get();
        if (existing != -1L) {
            return existing;
        }
        suiteStartNanos.compareAndSet(-1L, nowNanos);
        return suiteStartNanos.get();
    }

    /** Pushes the active test class FQCN for the current thread (avoiding duplicate consecutive pushes). */
    public void enterThreadClass(String fqcn) {
        Deque<String> stack = activeClassStack.get();
        if (!fqcn.equals(stack.peek())) {
            stack.push(fqcn);
        }
    }

    /** Pops the active test class FQCN for the current thread if present at the top. */
    public void exitThreadClass(String fqcn) {
        Deque<String> stack = activeClassStack.get();
        if (fqcn.equals(stack.peek())) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            activeClassStack.remove();
        }
    }

    /** Returns the active test class FQCN for the current thread, or {@code null} if outside a class. */
    public String currentThreadClass() {
        Deque<String> stack = activeClassStack.get();
        String top = stack.peek();
        if (stack.isEmpty()) {
            activeClassStack.remove();
        }
        return top;
    }

    /** Records outermost class start from JUnit Platform or Jupiter callbacks. */
    public void recordClassStarted(Class<?> testClass, String threadName, long nowNanos) {
        long suiteStart = ensureSuiteStarted(nowNanos);
        ClassTiming timing = getOrCreateClassTiming(testClass);
        timing.threadName = threadName;
        timing.startOffsetNanos.compareAndSet(-1L, Math.max(0L, nowNanos - suiteStart));
        enterThreadClass(testClass.getName());
    }

    /** Records outermost class completion from JUnit Platform or Jupiter callbacks. */
    public void recordClassFinished(Class<?> testClass, boolean failed, long nowNanos) {
        long suiteStart = ensureSuiteStarted(nowNanos);
        ClassTiming timing = getOrCreateClassTiming(testClass);
        if (failed) {
            timing.failedClass = true;
        }
        long endOffset = Math.max(0L, nowNanos - suiteStart);
        timing.endOffsetNanos.set(endOffset);
        long startOffset = timing.startOffsetNanos.get();
        if (startOffset >= 0L) {
            timing.wallNanos.set(Math.max(0L, endOffset - startOffset));
        }
        exitThreadClass(testClass.getName());
    }

    /** Records phase durations accumulated within a test class. */
    public void recordClassPhases(
            Class<?> testClass,
            long beforeAllNanos,
            long prepareInstanceNanos,
            long beforeEachNanos,
            long testBodyNanos,
            long afterEachNanos,
            long afterAllNanos) {
        ClassTiming timing = getOrCreateClassTiming(testClass);
        if (beforeAllNanos > 0L) {
            timing.beforeAllNanos.add(beforeAllNanos);
        }
        if (prepareInstanceNanos > 0L) {
            timing.prepareInstanceNanos.add(prepareInstanceNanos);
        }
        if (beforeEachNanos > 0L) {
            timing.beforeEachNanos.add(beforeEachNanos);
        }
        if (testBodyNanos > 0L) {
            timing.testBodyNanos.add(testBodyNanos);
        }
        if (afterEachNanos > 0L) {
            timing.afterEachNanos.add(afterEachNanos);
        }
        if (afterAllNanos > 0L) {
            timing.afterAllNanos.add(afterAllNanos);
        }
    }

    /** Records a single test method execution timing. */
    public void recordMethodExecution(Class<?> testClass, String methodName, long durationNanos, boolean failed) {
        ClassTiming classTiming = getOrCreateClassTiming(testClass);
        classTiming.testCount.increment();
        if (failed) {
            classTiming.failedTestCount.increment();
        }
        String key = testClass.getName() + "#" + methodName;
        MethodTiming mt = methods.computeIfAbsent(key, k -> new MethodTiming(testClass.getName(), methodName));
        mt.invocations.increment();
        mt.totalNanos.add(Math.max(0L, durationNanos));
        updateMax(mt.maxNanos, Math.max(0L, durationNanos));
        if (failed) {
            mt.failures.increment();
        }
    }

    /** Records Testcontainers Postgres + LiteLLM bootstrap breakdown from {@link PostgresTestInitializer}. */
    public void recordContainerBootstrap(ContainerBootstrapStats stats) {
        containerBootstrap.compareAndSet(null, stats);
    }

    /** Records pre-suite or post-suite Docker cleanup duration. */
    public void recordDockerCleanup(boolean preSuite, long nanos) {
        if (preSuite) {
            preSuiteDockerCleanupNanos.add(Math.max(0L, nanos));
        } else {
            postSuiteDockerCleanupNanos.add(Math.max(0L, nanos));
        }
    }

    /** Marks the start of {@code TestInstancePostProcessor} for a test class to capture context-lock wait time. */
    public void markPrepareInstanceStarted(Class<?> testClass, long nowNanos) {
        ClassTiming ct = getOrCreateClassTiming(testClass);
        ct.firstPrepareStartNanos.compareAndSet(-1L, nowNanos);
    }

    /** Registers a newly initialized Spring ApplicationContext and its Postgres schema. */
    public void registerContextInit(
            ConfigurableApplicationContext context, String schema, long schemaCreateNanos, long initStartNanos) {
        long suiteStart = ensureSuiteStarted(initStartNanos);
        String activeClass = currentThreadClass();
        ContextTrace trace = contexts.computeIfAbsent(
                new IdentityKey(context),
                k -> new ContextTrace(
                        "CTX-" + String.format(Locale.ROOT, "%02d", contextSeq.incrementAndGet()),
                        initStartNanos,
                        Math.max(0L, initStartNanos - suiteStart),
                        Thread.currentThread().getName(),
                        activeClass));
        trace.schema = schema;
        trace.schemaCreateNanos.add(Math.max(0L, schemaCreateNanos));
        if (activeClass != null) {
            ClassTiming ct = classes.get(activeClass);
            if (ct != null) {
                long prepStart = ct.firstPrepareStartNanos.get();
                if (prepStart > 0L && initStartNanos > prepStart) {
                    trace.lockWaitBeforeInitNanos.compareAndSet(-1L, initStartNanos - prepStart);
                }
            }
        }
    }

    /** Attaches slot assignment and cache-key differentiators when Spring customizes a new context. */
    public void recordContextCustomizer(
            ConfigurableApplicationContext context,
            int slot,
            String slotMode,
            Class<?> configTestClass,
            List<String> differentiators) {
        long now = System.nanoTime();
        long suiteStart = ensureSuiteStarted(now);
        ContextTrace trace = contexts.computeIfAbsent(
                new IdentityKey(context),
                k -> new ContextTrace(
                        "CTX-" + String.format(Locale.ROOT, "%02d", contextSeq.incrementAndGet()),
                        now,
                        Math.max(0L, now - suiteStart),
                        Thread.currentThread().getName(),
                        configTestClass != null ? configTestClass.getName() : currentThreadClass()));
        trace.slot = slot;
        trace.slotMode = slotMode;
        if (configTestClass != null && trace.triggerClass == null) {
            trace.triggerClass = configTestClass.getName();
        }
        if (differentiators != null && !differentiators.isEmpty()) {
            trace.differentiators.addAll(differentiators);
        }
    }

    /** Records initialization duration of key infrastructure beans (Liquibase, Hibernate EMF). */
    public void recordBeanInitDuration(ConfigurableApplicationContext context, String beanName, long nanos) {
        ContextTrace trace = contexts.get(new IdentityKey(context));
        if (trace == null) {
            return;
        }
        if ("liquibase".equals(beanName)) {
            trace.liquibaseNanos.add(Math.max(0L, nanos));
        } else if ("entityManagerFactory".equals(beanName)) {
            trace.entityManagerFactoryNanos.add(Math.max(0L, nanos));
        }
    }

    /** Records {@code ContextRefreshedEvent} completion on a Spring context. */
    public void recordContextRefreshed(ConfigurableApplicationContext context, long nowNanos) {
        ContextTrace trace = contexts.get(new IdentityKey(context));
        if (trace != null) {
            trace.refreshDurationNanos.compareAndSet(-1L, Math.max(0L, nowNanos - trace.initStartNanos));
        }
    }

    /** Records {@code ApplicationReadyEvent} completion on a Spring context. */
    public void recordContextReady(ConfigurableApplicationContext context, long nowNanos) {
        ContextTrace trace = contexts.get(new IdentityKey(context));
        if (trace != null) {
            trace.readyDurationNanos.compareAndSet(-1L, Math.max(0L, nowNanos - trace.initStartNanos));
        }
    }

    /** Records {@code ContextClosedEvent} to detect early eviction or {@code @DirtiesContext} teardown. */
    public void recordContextClosed(ConfigurableApplicationContext context, long nowNanos) {
        ContextTrace trace = contexts.get(new IdentityKey(context));
        if (trace != null) {
            long suiteStart = ensureSuiteStarted(nowNanos);
            trace.closedOffsetNanos.compareAndSet(-1L, Math.max(0L, nowNanos - suiteStart));
            if (!suiteFinished.get()) {
                trace.closedDuringRun = true;
            }
        }
    }

    /**
     * Records which {@link ConfigurableApplicationContext} a test class acquired, and whether that
     * acquisition was a Spring TestContext cache hit or miss.
     */
    public void recordClassContextUsage(
            Class<?> testClass,
            ConfigurableApplicationContext context,
            boolean cacheHitBeforeAcquire,
            long acquireNanos) {
        ClassTiming classTiming = getOrCreateClassTiming(testClass);
        ContextTrace trace = contexts.get(new IdentityKey(context));
        if (trace == null) {
            long now = System.nanoTime();
            long suiteStart = ensureSuiteStarted(now);
            trace = contexts.computeIfAbsent(
                    new IdentityKey(context),
                    k -> new ContextTrace(
                            "CTX-" + String.format(Locale.ROOT, "%02d", contextSeq.incrementAndGet()),
                            now,
                            Math.max(0L, now - suiteStart),
                            Thread.currentThread().getName(),
                            testClass.getName()));
        }
        boolean isFirstClassOnContext = trace.userClasses.add(testClass.getName())
                && Objects.equals(trace.triggerClass, testClass.getName())
                && !cacheHitBeforeAcquire;
        boolean hit = cacheHitBeforeAcquire || !isFirstClassOnContext;
        if (classTiming.recordedContextIds.add(trace.ctxId)) {
            if (hit) {
                trace.cacheHits.increment();
            }
            if (classTiming.contextId == null) {
                classTiming.contextId = trace.ctxId;
                classTiming.schema = trace.schema;
                classTiming.cacheHit = hit;
            } else {
                classTiming.contextId = classTiming.contextId + "+" + trace.ctxId;
            }
        }
        classTiming.contextAcquireNanos.add(Math.max(0L, acquireNanos));
    }

    /** Records {@code DatabaseCleaner.cleanAll()} timings globally and for the active class. */
    public void recordDbClean(
            long totalNanos, long litellmNanos, long discoveryNanos, long truncateNanos, boolean lockTimeoutHealed) {
        long clamped = Math.max(0L, totalNanos);
        dbCleanCalls.increment();
        dbCleanTotalNanos.add(clamped);
        dbCleanLitellmNanos.add(Math.max(0L, litellmNanos));
        dbCleanDiscoveryNanos.add(Math.max(0L, discoveryNanos));
        dbCleanTruncateNanos.add(Math.max(0L, truncateNanos));
        if (lockTimeoutHealed) {
            dbCleanLockHeals.increment();
        }
        updateMax(dbCleanMaxNanos, clamped);

        String fqcn = currentThreadClass();
        if (fqcn != null) {
            ClassTiming ct = classes.get(fqcn);
            if (ct != null) {
                ct.dbCleanCalls.increment();
                ct.dbCleanNanos.add(clamped);
                ct.dbCleanLitellmNanos.add(Math.max(0L, litellmNanos));
                ct.dbCleanTruncateNanos.add(Math.max(0L, truncateNanos));
                if (lockTimeoutHealed) {
                    ct.dbCleanLockHeals.increment();
                }
            }
        }
    }

    /** Records {@code DrainExecutorsTestExecutionListener} drain timings and waited bean names. */
    public void recordDrainExecutors(long nanos, boolean afterTestExec, List<String> waitedBeans) {
        long clamped = Math.max(0L, nanos);
        drainCalls.increment();
        drainTotalNanos.add(clamped);
        updateMax(drainMaxNanos, clamped);
        boolean hadActiveTask = waitedBeans != null && !waitedBeans.isEmpty();
        if (hadActiveTask) {
            drainBusyCalls.increment();
            drainBusyNanos.add(clamped);
            for (String bean : waitedBeans) {
                drainWaitedBeans.computeIfAbsent(bean, k -> new LongAdder()).increment();
            }
        } else {
            drainIdleCalls.increment();
            drainIdleNanos.add(clamped);
        }
        boolean waited = clamped >= DRAIN_WAIT_THRESHOLD_NANOS || hadActiveTask;
        if (waited) {
            drainWaitEvents.increment();
        }

        String fqcn = currentThreadClass();
        if (fqcn != null) {
            ClassTiming ct = classes.get(fqcn);
            if (ct != null) {
                ct.drainCalls.increment();
                ct.drainNanos.add(clamped);
                if (!hadActiveTask) {
                    ct.drainIdleCalls.increment();
                    ct.drainIdleNanos.add(clamped);
                }
                if (afterTestExec) {
                    ct.drainAfterTestExecNanos.add(clamped);
                }
                if (waited) {
                    ct.drainWaitEvents.increment();
                }
            }
        }
    }

    /** Records {@code ResetChatModelTestExecutionListener} execution time. */
    public void recordChatModelReset(long nanos) {
        long clamped = Math.max(0L, nanos);
        chatModelResetCalls.increment();
        chatModelResetTotalNanos.add(clamped);
    }

    /** Records a subprocess invocation from {@link ProcessExecutorTestConfig}. */
    public void recordProcessExec(List<String> command, long nanos, int exitCode) {
        long clamped = Math.max(0L, nanos);
        String category = categorizeCommand(command);
        ProcessCategoryStats stats = processCategories.computeIfAbsent(category, ProcessCategoryStats::new);
        stats.calls.increment();
        stats.totalNanos.add(clamped);
        updateMax(stats.maxNanos, clamped);
        if (exitCode != 0) {
            stats.nonZeroExits.increment();
        }

        String fqcn = currentThreadClass();
        if (fqcn != null) {
            ClassTiming ct = classes.get(fqcn);
            if (ct != null) {
                ct.processCalls.increment();
                ct.processNanos.add(clamped);
                if ("codebase-memory-mcp".equals(category) || "syft".equals(category)) {
                    ct.realParserCommands.increment();
                    ct.realParserNames.add(category);
                }
            }
        }
    }

    /** Records a named sub-phase duration (for E2E/Docker/fixture lifecycle breakdown) for a class. */
    public void recordSubPhase(Class<?> testClass, String phaseName, long nanos) {
        if (testClass == null || phaseName == null || nanos <= 0L) {
            return;
        }
        ClassTiming ct = getOrCreateClassTiming(testClass);
        ct.subPhases.computeIfAbsent(phaseName, k -> new LongAdder()).add(nanos);
    }

    /** Records a named sub-phase duration attributed to the current thread's active test class. */
    public void recordSubPhase(String phaseName, long nanos) {
        if (phaseName == null || nanos <= 0L) {
            return;
        }
        String fqcn = currentThreadClass();
        if (fqcn != null) {
            ClassTiming ct = classes.get(fqcn);
            if (ct != null) {
                ct.subPhases.computeIfAbsent(phaseName, k -> new LongAdder()).add(nanos);
            }
        }
    }

    /** Returns total context refreshes observed in this registry. */
    public int getContextRefreshCount() {
        return contexts.size();
    }

    /** Returns total context cache hits across classes in this registry. */
    public int getContextCacheHitCount() {
        return contexts.values().stream().mapToInt(c -> (int) c.cacheHits.sum()).sum();
    }

    /** Returns total time spent refreshing Spring contexts in nanoseconds. */
    public long getTotalContextRefreshNanos() {
        return contexts.values().stream()
                .mapToLong(c -> Math.max(0L, c.refreshDurationNanos.get()))
                .sum();
    }

    /**
     * Evaluates structural and performance regression gates for a completed test class. Returns a
     * list of human-readable violation messages (empty when all gates pass).
     */
    public List<String> validateClassGates(Class<?> testClass) {
        if (testClass == null) {
            return List.of();
        }
        ClassTiming ct = classes.get(testClass.getName());
        if (ct == null) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();

        if (ct.springIntegrationTest && ct.slot != SLOT_NONE) {
            List<ContextTrace> slotContexts = contexts.values().stream()
                    .filter(c -> c.slot == ct.slot)
                    .sorted(Comparator.comparing(c -> c.ctxId))
                    .toList();
            if (slotContexts.size() > 1) {
                String details = slotContexts.stream()
                        .map(c -> c.ctxId
                                + "("
                                + simpleName(c.triggerClass)
                                + ", diff="
                                + (c.differentiators.isEmpty() ? "<default>" : String.join(",", c.differentiators))
                                + ")")
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("");
                violations.add(String.format(
                        Locale.ROOT,
                        "SLOT_%d forked %d Spring contexts (%s). Keep 1 context per slot via TestSpiesConfig and mutable properties.",
                        ct.slot,
                        slotContexts.size(),
                        details));
            }
            for (ContextTrace c : slotContexts) {
                if (c.closedDuringRun && c.userClasses.contains(ct.fqcn)) {
                    violations.add(String.format(
                            Locale.ROOT,
                            "Context %s (SLOT_%d) closed mid-suite during %s (avoid @DirtiesContext / cache eviction).",
                            c.ctxId,
                            ct.slot,
                            ct.simpleName));
                }
            }
        }

        if (ct.drainIdleCalls.sum() >= 2 && ct.drainIdleNanos.sum() > MAX_CLASS_IDLE_DRAIN_NANOS) {
            violations.add(String.format(
                    Locale.ROOT,
                    "DrainExecutors idle-pool overhead reached %.2fs across %d idle calls (budget <= %.2fs); check Tracking* awaitIdle fast-path.",
                    toSec(ct.drainIdleNanos.sum()),
                    ct.drainIdleCalls.sum(),
                    toSec(MAX_CLASS_IDLE_DRAIN_NANOS)));
        }

        if (ct.realParserCommands.sum() > 0 && !ct.usesRealNativeBinaries) {
            violations.add(String.format(
                    Locale.ROOT,
                    "Executed real native binaries (%s, %d call(s)) without @UseRealNativeBinaries; annotate with @UseRealNativeBinaries or stub via IngestionTestStubs.",
                    String.join(", ", ct.realParserNames),
                    ct.realParserCommands.sum()));
        }
        return violations;
    }

    static boolean isRealParserOrSbomAllowed(Class<?> testClass) {
        if (testClass == null) {
            return false;
        }
        if (hasAnnotationInHierarchy(testClass, UseRealNativeBinaries.class)) {
            return true;
        }
        for (var method : testClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(UseRealNativeBinaries.class)) {
                return true;
            }
        }
        return false;
    }

    /** Emits formatted report to stdout and writes report files under {@code target/} once per run. */
    public void emitReportsIfNotYetEmitted() {
        if (!reportEmitted.compareAndSet(false, true)) {
            return;
        }
        if (classes.isEmpty() && methods.isEmpty() && contexts.isEmpty()) {
            return;
        }
        markSuiteFinished();
        ReportSnapshot snapshot = buildSnapshot();
        String summary = formatSummaryReport(snapshot);
        System.out.println(summary);
        writeSummaryFile(summary, Path.of("target/test-timing-summary.txt"));
        writeMainCsv(snapshot, Path.of("target/test-timing-report.csv"));
        writeContextsCsv(snapshot, Path.of("target/test-timing-contexts.csv"));
        writeTimelineCsv(snapshot, Path.of("target/test-timing-timeline.csv"));
    }

    /** Builds a deterministic snapshot of all recorded metrics for reporting and testing. */
    public ReportSnapshot buildSnapshot() {
        long start = suiteStartNanos.get();
        long end = suiteEndNanos.get();
        long now = System.nanoTime();
        if (start < 0L) {
            start = now;
        }
        if (end < start) {
            end = now;
        }
        long suiteWallNanos = Math.max(0L, end - start);

        List<ClassTiming> classList = classes.values().stream()
                .sorted(Comparator.comparingLong((ClassTiming c) ->
                                c.startOffsetNanos.get() >= 0L ? c.startOffsetNanos.get() : Long.MAX_VALUE)
                        .thenComparing(c -> c.fqcn))
                .toList();

        long maxClassEndOffset = classList.stream()
                .mapToLong(c -> Math.max(0L, c.endOffsetNanos.get()))
                .max()
                .orElse(0L);
        long effectiveWallNanos = Math.max(suiteWallNanos, maxClassEndOffset);

        ConcurrencyStats concurrency = computeConcurrencyStats(classList, effectiveWallNanos);
        List<WorkerThreadStats> workerStats = computeWorkerThreadStats(classList, effectiveWallNanos);
        List<SlotStats> slotStats = computeSlotStats(classList, contexts.values());

        List<ContextTrace> contextList = contexts.values().stream()
                .sorted(Comparator.comparing(c -> c.ctxId))
                .toList();

        List<MethodTiming> methodList = new ArrayList<>(methods.values());
        List<ProcessCategoryStats> processList = processCategories.values().stream()
                .sorted(Comparator.comparingLong((ProcessCategoryStats p) -> p.totalNanos.sum())
                        .reversed())
                .toList();

        return new ReportSnapshot(
                effectiveWallNanos,
                containerBootstrap.get(),
                preSuiteDockerCleanupNanos.sum(),
                postSuiteDockerCleanupNanos.sum(),
                classList,
                methodList,
                contextList,
                workerStats,
                slotStats,
                concurrency,
                processList,
                dbCleanCalls.sum(),
                dbCleanTotalNanos.sum(),
                dbCleanLitellmNanos.sum(),
                dbCleanDiscoveryNanos.sum(),
                dbCleanTruncateNanos.sum(),
                dbCleanLockHeals.sum(),
                dbCleanMaxNanos.get(),
                drainCalls.sum(),
                drainTotalNanos.sum(),
                drainIdleCalls.sum(),
                drainIdleNanos.sum(),
                drainBusyCalls.sum(),
                drainBusyNanos.sum(),
                drainWaitEvents.sum(),
                drainMaxNanos.get(),
                new TreeMap<>(drainWaitedBeans),
                chatModelResetCalls.sum(),
                chatModelResetTotalNanos.sum());
    }

    /** Formats the full human-readable analysis report from a snapshot. */
    public String formatSummaryReport(ReportSnapshot s) {
        StringBuilder out = new StringBuilder(4096);
        out.append('\n');
        out.append(
                "====================================================================================================\n");
        out.append("TEST EXECUTION TIMING & BOTTLENECK REPORT\n");
        out.append(
                "====================================================================================================\n");

        long totalClassWallNanos =
                s.classes.stream().mapToLong(c -> c.wallNanos.get()).sum();
        int totalTests =
                s.classes.stream().mapToInt(c -> (int) c.testCount.sum()).sum();
        int failedTests =
                s.classes.stream().mapToInt(c -> (int) c.failedTestCount.sum()).sum();
        double effectiveParallelism =
                s.suiteWallNanos > 0L ? (double) totalClassWallNanos / (double) s.suiteWallNanos : 0.0;

        out.append(String.format(
                Locale.ROOT,
                "Suite Wall Clock: %8.2fs | Cumulative Class Time: %8.2fs | Effective Parallelism: %.2fx%n",
                toSec(s.suiteWallNanos),
                toSec(totalClassWallNanos),
                effectiveParallelism));
        out.append(String.format(
                Locale.ROOT,
                "Test Classes: %d (%d @SpringIntegrationTest, %d @SlowTest) | Test Methods: %d (failed: %d)%n",
                s.classes.size(),
                (int) s.classes.stream().filter(c -> c.springIntegrationTest).count(),
                (int) s.classes.stream().filter(c -> c.slowTest).count(),
                totalTests,
                failedTests));

        if (s.preSuiteDockerCleanupNanos > 0L || s.postSuiteDockerCleanupNanos > 0L) {
            out.append(String.format(
                    Locale.ROOT,
                    "Docker Hygiene Listener: pre-suite=%.2fs, post-suite=%.2fs%n",
                    toSec(s.preSuiteDockerCleanupNanos),
                    toSec(s.postSuiteDockerCleanupNanos)));
        }

        // Concurrency & single-thread tail
        ConcurrencyStats cc = s.concurrency;
        out.append('\n');
        out.append(
                "── Concurrency & Tail Serialization ────────────────────────────────────────────────────────────────\n");
        out.append(String.format(
                Locale.ROOT,
                "Peak active classes: %d | Idle(0): %.2fs (%.1f%%) | 1-active: %.2fs (%.1f%%) | 2-active: %.2fs (%.1f%%)"
                        + " | 3-active: %.2fs (%.1f%%) | 4+-active: %.2fs (%.1f%%)%n",
                cc.peakConcurrency,
                toSec(cc.nanosByConcurrency.getOrDefault(0, 0L)),
                pct(cc.nanosByConcurrency.getOrDefault(0, 0L), s.suiteWallNanos),
                toSec(cc.nanosByConcurrency.getOrDefault(1, 0L)),
                pct(cc.nanosByConcurrency.getOrDefault(1, 0L), s.suiteWallNanos),
                toSec(cc.nanosByConcurrency.getOrDefault(2, 0L)),
                pct(cc.nanosByConcurrency.getOrDefault(2, 0L), s.suiteWallNanos),
                toSec(cc.nanosByConcurrency.getOrDefault(3, 0L)),
                pct(cc.nanosByConcurrency.getOrDefault(3, 0L), s.suiteWallNanos),
                toSec(cc.fourPlusNanos),
                pct(cc.fourPlusNanos, s.suiteWallNanos)));
        out.append(String.format(
                Locale.ROOT,
                "Single-Thread Tail (from %.2fs to %.2fs): %.2fs (%.1f%% of suite wall clock!) — tail classes (%d): %s%n",
                toSec(cc.tailStartOffsetNanos),
                toSec(s.suiteWallNanos),
                toSec(cc.tailNanos),
                pct(cc.tailNanos, s.suiteWallNanos),
                cc.tailClasses.size(),
                String.join(", ", cc.tailClasses)));

        // Container bootstrap
        if (s.containerBootstrap != null) {
            ContainerBootstrapStats cb = s.containerBootstrap;
            out.append('\n');
            out.append(
                    "── PostgresTestInitializer Static Bootstrap ────────────────────────────────────────────────────────\n");
            out.append(String.format(
                    Locale.ROOT,
                    "Total: %.2fs (thread=%s, class=%s) | network=%.2fs | pgStart=%.2fs | pgInit=%.2fs | litellmStart=%.2fs"
                            + " | patchVerify=%.2fs | modelCountCheck=%.2fs%n",
                    toSec(cb.totalNanos),
                    cb.triggeredByThread,
                    cb.triggeredByClass,
                    toSec(cb.networkNanos),
                    toSec(cb.postgresStartNanos),
                    toSec(cb.postgresInitNanos),
                    toSec(cb.litellmStartNanos),
                    toSec(cb.patchVerifyNanos),
                    toSec(cb.modelCountCheckNanos)));
        }

        // Cumulative Phase Breakdown
        long totalCtxRefreshNanos = s.contexts.stream()
                .mapToLong(c -> Math.max(0L, c.refreshDurationNanos.get()))
                .sum();
        long totalLiquibaseNanos =
                s.contexts.stream().mapToLong(c -> c.liquibaseNanos.sum()).sum();
        long totalJpaEmfNanos = s.contexts.stream()
                .mapToLong(c -> c.entityManagerFactoryNanos.sum())
                .sum();
        long totalBeforeAllNanos =
                s.classes.stream().mapToLong(c -> c.beforeAllNanos.sum()).sum();
        long totalPrepareNanos =
                s.classes.stream().mapToLong(c -> c.prepareInstanceNanos.sum()).sum();
        long totalBeforeEachNanos =
                s.classes.stream().mapToLong(c -> c.beforeEachNanos.sum()).sum();
        long totalTestBodyNanos =
                s.classes.stream().mapToLong(c -> c.testBodyNanos.sum()).sum();
        long totalAfterEachNanos =
                s.classes.stream().mapToLong(c -> c.afterEachNanos.sum()).sum();
        long totalAfterAllNanos =
                s.classes.stream().mapToLong(c -> c.afterAllNanos.sum()).sum();
        long totalDrainAfterExecNanos = s.classes.stream()
                .mapToLong(c -> c.drainAfterTestExecNanos.sum())
                .sum();
        long pureTestBodyNanos = Math.max(0L, totalTestBodyNanos - totalDrainAfterExecNanos);

        out.append('\n');
        out.append(
                "── Cumulative Phase Breakdown (Sum Across All Threads) ─────────────────────────────────────────────\n");
        out.append(String.format(
                Locale.ROOT,
                "BeforeAll: %7.2fs (%4.1f%%) | PrepareInstance (incl. ctx load): %7.2fs (%4.1f%%) [Spring Context Refreshes: %7.2fs; Liquibase: %.2fs; JPA EMF: %.2fs]%n",
                toSec(totalBeforeAllNanos),
                pct(totalBeforeAllNanos, totalClassWallNanos),
                toSec(totalPrepareNanos),
                pct(totalPrepareNanos, totalClassWallNanos),
                toSec(totalCtxRefreshNanos),
                toSec(totalLiquibaseNanos),
                toSec(totalJpaEmfNanos)));
        out.append(String.format(
                Locale.ROOT,
                "BeforeEach: %7.2fs (%4.1f%%) | Pure Test Body: %7.2fs (%4.1f%%) | AfterEach: %7.2fs (%4.1f%%) | AfterAll: %7.2fs (%4.1f%%)%n",
                toSec(totalBeforeEachNanos),
                pct(totalBeforeEachNanos, totalClassWallNanos),
                toSec(pureTestBodyNanos),
                pct(pureTestBodyNanos, totalClassWallNanos),
                toSec(totalAfterEachNanos),
                pct(totalAfterEachNanos, totalClassWallNanos),
                toSec(totalAfterAllNanos),
                pct(totalAfterAllNanos, totalClassWallNanos)));
        out.append(String.format(
                Locale.ROOT,
                "DatabaseCleaner.cleanAll(): %d calls, total=%.2fs (%4.1f%%), max=%.2fs [litellm=%.2fs, discovery=%.2fs, truncate=%.2fs, lockHeals=%d]%n",
                s.dbCleanCalls,
                toSec(s.dbCleanTotalNanos),
                pct(s.dbCleanTotalNanos, totalClassWallNanos),
                toSec(s.dbCleanMaxNanos),
                toSec(s.dbCleanLitellmNanos),
                toSec(s.dbCleanDiscoveryNanos),
                toSec(s.dbCleanTruncateNanos),
                s.dbCleanLockHeals));
        out.append(String.format(
                Locale.ROOT,
                "DrainExecutorsListener: %d calls, total=%.2fs (%4.1f%%), max=%.2fs [idle-pool overhead (0 active): %d calls=%.2fs | busy waits: %d calls=%.2fs] | ChatModelReset: %d calls, %.2fs%n",
                s.drainCalls,
                toSec(s.drainTotalNanos),
                pct(s.drainTotalNanos, totalClassWallNanos),
                toSec(s.drainMaxNanos),
                s.drainIdleCalls,
                toSec(s.drainIdleNanos),
                s.drainBusyCalls,
                toSec(s.drainBusyNanos),
                s.chatModelResetCalls,
                toSec(s.chatModelResetTotalNanos)));
        if (!s.drainWaitedBeans.isEmpty()) {
            String beans = s.drainWaitedBeans.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue().sum())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            out.append("  Drain waits by bean: ").append(beans).append('\n');
        }

        // Per-Worker Thread Utilization
        out.append('\n');
        out.append(
                "── Worker Thread Utilization ───────────────────────────────────────────────────────────────────────\n");
        out.append(String.format(
                Locale.ROOT,
                "%-28s %7s %9s %7s %9s %9s %9s %9s%n",
                "Thread",
                "Classes",
                "Busy(s)",
                "Util%",
                "Gap(s)",
                "Start(s)",
                "End(s)",
                "TailIdle"));
        for (WorkerThreadStats w : s.workers) {
            out.append(String.format(
                    Locale.ROOT,
                    "%-28s %7d %9.2f %6.1f%% %9.2f %9.2f %9.2f %8.2fs%n",
                    truncate(w.threadName, 28),
                    w.classCount,
                    toSec(w.busyNanos),
                    pct(w.busyNanos, s.suiteWallNanos),
                    toSec(w.interClassGapNanos),
                    toSec(w.firstStartOffsetNanos),
                    toSec(w.lastEndOffsetNanos),
                    toSec(w.tailIdleNanos)));
        }

        // Per-Slot & SlotGroup Breakdown
        out.append('\n');
        out.append(
                "── Slot & SlotGroup Breakdown ──────────────────────────────────────────────────────────────────────\n");
        out.append(String.format(
                Locale.ROOT,
                "%-26s %-15s %7s %6s %9s %9s %6s %6s %9s %9s %9s%n",
                "Slot",
                "Mode/Group",
                "Classes",
                "Tests",
                "Busy(s)",
                "Span(s)",
                "Ctxs",
                "Hits",
                "CtxBoot",
                "DbClean",
                "PureTest"));
        for (SlotStats sl : s.slots) {
            out.append(String.format(
                    Locale.ROOT,
                    "%-26s %-15s %7d %6d %9.2f %9.2f %6d %6d %9.2f %9.2f %9.2f%n",
                    sl.slotLabel,
                    truncate(sl.modeSummary, 15),
                    sl.classCount,
                    sl.testCount,
                    toSec(sl.busyNanos),
                    toSec(sl.spanNanos),
                    sl.contextsCreated,
                    sl.cacheHits,
                    toSec(sl.contextRefreshNanos),
                    toSec(sl.dbCleanNanos),
                    toSec(sl.pureTestNanos)));
        }

        // Spring Context Cache & Sharing Report
        int totalHits =
                s.contexts.stream().mapToInt(c -> (int) c.cacheHits.sum()).sum();
        int totalCtxs = s.contexts.size();
        int totalAcquires = totalCtxs + totalHits;
        double hitRate = totalAcquires > 0 ? (100.0 * totalHits) / totalAcquires : 0.0;
        long earlyClosures = s.contexts.stream().filter(c -> c.closedDuringRun).count();

        long totalPreInitWaitNanos = s.contexts.stream()
                .mapToLong(c -> Math.max(0L, c.lockWaitBeforeInitNanos.get()))
                .sum();
        List<ClassTiming> cacheHitStalled = s.classes.stream()
                .filter(c -> c.contextId != null && c.cacheHit && c.prepareInstanceNanos.sum() > 100L * NANOS_PER_MS)
                .toList();
        long cacheHitStallNanos = cacheHitStalled.stream()
                .mapToLong(c -> c.prepareInstanceNanos.sum())
                .sum();

        out.append('\n');
        out.append(
                "── Spring ApplicationContext Lifecycle & Cache Audit ───────────────────────────────────────────────\n");
        out.append(String.format(
                Locale.ROOT,
                "Contexts Created (Misses): %d | Cache Hits: %d (Hit Rate: %.1f%%) | Total Refresh: %.2fs (avg=%.2fs) | Pre-Init Lock Wait: %.2fs | Early Closures/Evictions: %d%n",
                totalCtxs,
                totalHits,
                hitRate,
                toSec(totalCtxRefreshNanos),
                totalCtxs > 0 ? toSec(totalCtxRefreshNanos) / totalCtxs : 0.0,
                toSec(totalPreInitWaitNanos),
                earlyClosures));
        if (!cacheHitStalled.isEmpty()) {
            out.append(String.format(
                    Locale.ROOT,
                    "  [!] Cache-Hit Lock Contention: %d cache-hit classes waited >100ms in prepareInstance (total=%.2fs) blocked by ContextCache lock!%n",
                    cacheHitStalled.size(),
                    toSec(cacheHitStallNanos)));
        }
        out.append(String.format(
                Locale.ROOT,
                "%-7s %-7s %-16s %-14s %7s %7s %7s %7s %6s %5s %5s  %-28s  %s%n",
                "CtxId",
                "Slot",
                "SlotMode",
                "Schema",
                "Wait(s)",
                "Boot(s)",
                "Liq(s)",
                "JPA(s)",
                "Early",
                "Users",
                "Hits",
                "TriggerClass",
                "Differentiators"));
        for (ContextTrace c : s.contexts) {
            out.append(String.format(
                    Locale.ROOT,
                    "%-7s %-7s %-16s %-14s %7.2f %7.2f %7.2f %7.2f %6s %5d %5d  %-28s  %s%n",
                    c.ctxId,
                    c.slot == SLOT_NONE ? "NONE" : "SLOT_" + c.slot,
                    truncate(c.slotMode == null ? "?" : c.slotMode, 16),
                    truncate(c.schema == null ? "?" : c.schema, 14),
                    toSec(Math.max(0L, c.lockWaitBeforeInitNanos.get())),
                    toSec(Math.max(0L, c.refreshDurationNanos.get())),
                    toSec(c.liquibaseNanos.sum()),
                    toSec(c.entityManagerFactoryNanos.sum()),
                    c.closedDuringRun ? "YES" : "no",
                    c.userClasses.size(),
                    c.cacheHits.sum(),
                    truncate(simpleName(c.triggerClass), 28),
                    c.differentiators.isEmpty() ? "<default>" : String.join("; ", c.differentiators)));
        }

        // Flag slots with >1 context
        Map<Integer, List<ContextTrace>> bySlot = new TreeMap<>();
        for (ContextTrace c : s.contexts) {
            bySlot.computeIfAbsent(c.slot, k -> new ArrayList<>()).add(c);
        }
        boolean anyMultiCtxSlot = bySlot.values().stream().anyMatch(list -> list.size() > 1);
        if (anyMultiCtxSlot) {
            out.append("  [!] Slots with >1 Spring Context (context key forks inside the same slot):\n");
            for (Map.Entry<Integer, List<ContextTrace>> entry : bySlot.entrySet()) {
                if (entry.getValue().size() > 1) {
                    out.append(String.format(
                            Locale.ROOT,
                            "      SLOT_%d created %d contexts:%n",
                            entry.getKey(),
                            entry.getValue().size()));
                    for (ContextTrace c : entry.getValue()) {
                        out.append(String.format(
                                Locale.ROOT,
                                "        - %s (%s, boot=%.2fs, users=%d, diff=%s)%n",
                                c.ctxId,
                                simpleName(c.triggerClass),
                                toSec(Math.max(0L, c.refreshDurationNanos.get())),
                                c.userClasses.size(),
                                c.differentiators.isEmpty() ? "<default>" : String.join(", ", c.differentiators)));
                    }
                }
            }
        }

        // ProcessExecutor breakdown
        if (!s.processCategories.isEmpty()) {
            long procTotal = s.processCategories.stream()
                    .mapToLong(p -> p.totalNanos.sum())
                    .sum();
            out.append('\n');
            out.append(
                    "── ProcessExecutor Real Subprocess Breakdown ───────────────────────────────────────────────────────\n");
            out.append(String.format(
                    Locale.ROOT,
                    "Total subprocesses: %d calls, %.2fs cumulative:%n",
                    s.processCategories.stream()
                            .mapToInt(p -> (int) p.calls.sum())
                            .sum(),
                    toSec(procTotal)));
            out.append(String.format(
                    Locale.ROOT,
                    "%-24s %7s %9s %9s %9s %7s%n",
                    "Command",
                    "Calls",
                    "Total(s)",
                    "Avg(ms)",
                    "Max(s)",
                    "Errors"));
            for (ProcessCategoryStats p : s.processCategories) {
                long calls = p.calls.sum();
                long total = p.totalNanos.sum();
                out.append(String.format(
                        Locale.ROOT,
                        "%-24s %7d %9.2f %9.1f %9.2f %7d%n",
                        p.category,
                        calls,
                        toSec(total),
                        calls > 0 ? (double) total / NANOS_PER_MS / calls : 0.0,
                        toSec(p.maxNanos.get()),
                        p.nonZeroExits.sum()));
            }
        }

        // E2E Sub-phase Breakdown
        List<ClassTiming> classesWithSubPhases = s.classes.stream()
                .filter(c -> !c.subPhases.isEmpty())
                .sorted(Comparator.comparingLong((ClassTiming c) -> c.wallNanos.get())
                        .reversed())
                .toList();
        if (!classesWithSubPhases.isEmpty()) {
            out.append('\n');
            out.append(
                    "── E2E & Heavy Test Sub-Phase Breakdown ────────────────────────────────────────────────────────────\n");
            for (ClassTiming c : classesWithSubPhases) {
                String phases = c.subPhases.entrySet().stream()
                        .sorted(Map.Entry.<String, LongAdder>comparingByValue(Comparator.comparingLong(LongAdder::sum))
                                .reversed())
                        .map(e -> String.format(
                                Locale.ROOT,
                                "%s=%.2fs",
                                e.getKey(),
                                toSec(e.getValue().sum())))
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                out.append(String.format(
                        Locale.ROOT, "%-34s total=%7.2fs | %s%n", c.simpleName, toSec(c.wallNanos.get()), phases));
            }
        }

        // Top 25 Slowest Classes
        out.append('\n');
        out.append(
                "── Top 25 Slowest Test Classes ─────────────────────────────────────────────────────────────────────\n");
        out.append(String.format(
                Locale.ROOT,
                "%-34s %-7s %-14s %-7s %4s %8s %7s %7s %7s %7s %7s %7s%n",
                "Class",
                "Slot",
                "Group",
                "Ctx",
                "Hit",
                "Total",
                "BefAll",
                "Prep",
                "BefEa",
                "DbCln",
                "Test",
                "AftEa"));
        s.classes.stream()
                .sorted(Comparator.comparingLong((ClassTiming c) -> c.wallNanos.get())
                        .reversed())
                .limit(TOP_LIMIT)
                .forEach(c -> {
                    long pureTest = Math.max(0L, c.testBodyNanos.sum() - c.drainAfterTestExecNanos.sum());
                    out.append(String.format(
                            Locale.ROOT,
                            "%-34s %-7s %-14s %-7s %4s %7.2fs %6.2fs %6.2fs %6.2fs %6.2fs %6.2fs %6.2fs%n",
                            truncate(c.simpleName, 34),
                            c.slot == SLOT_NONE ? "NONE" : "SLOT_" + c.slot,
                            truncate(c.slotGroup.isEmpty() ? c.slotMode : c.slotGroup, 14),
                            c.contextId == null ? "-" : truncate(c.contextId, 7),
                            c.contextId == null ? "-" : (c.cacheHit ? "HIT" : "MISS"),
                            toSec(c.wallNanos.get()),
                            toSec(c.beforeAllNanos.sum()),
                            toSec(c.prepareInstanceNanos.sum()),
                            toSec(c.beforeEachNanos.sum()),
                            toSec(c.dbCleanNanos.sum()),
                            toSec(pureTest),
                            toSec(c.afterEachNanos.sum())));
                });

        // Top 25 Slowest Test Methods
        out.append('\n');
        out.append(
                "── Top 25 Slowest Individual Test Methods ──────────────────────────────────────────────────────────\n");
        s.methods.stream()
                .sorted(Comparator.comparingLong((MethodTiming m) -> m.totalNanos.sum())
                        .reversed())
                .limit(TOP_LIMIT)
                .forEach(m -> out.append(String.format(
                        Locale.ROOT,
                        "%7.2fs (%dx, max=%.2fs)  %s#%s%n",
                        toSec(m.totalNanos.sum()),
                        m.invocations.sum(),
                        toSec(m.maxNanos.get()),
                        simpleName(m.className),
                        m.methodName)));

        // Regression Gate Summary
        long forkedSlots = bySlot.entrySet().stream()
                .filter(e -> e.getKey() != SLOT_NONE && e.getValue().size() > 1)
                .count();
        long unexpectedRealBinaryClasses = s.classes.stream()
                .filter(c -> c.realParserCommands.sum() > 0 && !c.usesRealNativeBinaries)
                .count();
        long idleDrainViolations = s.classes.stream()
                .filter(c -> c.drainIdleCalls.sum() >= 2 && c.drainIdleNanos.sum() > MAX_CLASS_IDLE_DRAIN_NANOS)
                .count();
        out.append('\n');
        out.append(
                "── Regression Gates ────────────────────────────────────────────────────────────────────────────────\n");
        out.append(String.format(
                Locale.ROOT,
                "Context Forks (>1 ctx/slot): %d (%s) | Early Context Closures: %d (%s) | Idle Drain >2s Classes: %d (%s) | Unannotated Native-Binary Classes: %d (%s)%n",
                forkedSlots,
                forkedSlots == 0 ? "PASS" : "FAIL",
                earlyClosures,
                earlyClosures == 0 ? "PASS" : "FAIL",
                idleDrainViolations,
                idleDrainViolations == 0 ? "PASS" : "FAIL",
                unexpectedRealBinaryClasses,
                unexpectedRealBinaryClasses == 0 ? "PASS" : "FAIL"));

        out.append(
                "====================================================================================================\n");
        return out.toString();
    }

    private ClassTiming getOrCreateClassTiming(Class<?> testClass) {
        return classes.computeIfAbsent(testClass.getName(), k -> {
            boolean springInt = hasAnnotationInHierarchy(testClass, SpringIntegrationTest.class);
            boolean slow = hasAnnotationInHierarchy(testClass, SlowTest.class);
            SlowTest slowAnn = testClass.getAnnotation(SlowTest.class);
            String slotGroup = slowAnn != null ? slowAnn.slotGroup() : "";
            int slot = springInt ? ThreadBoundContextCustomizer.getSlotForClass(testClass) : SLOT_NONE;
            String slotMode = springInt ? ThreadBoundContextCustomizer.getSlotModeForClass(testClass) : "UNIT";
            boolean dirties = hasAnnotationInHierarchy(testClass, DirtiesContext.class);
            boolean transactional = hasAnnotationInHierarchy(testClass, Transactional.class);
            boolean realNativeBinaries = isRealParserOrSbomAllowed(testClass);
            return new ClassTiming(
                    testClass.getName(),
                    testClass.getSimpleName(),
                    springInt,
                    slow,
                    slotGroup,
                    slot,
                    slotMode,
                    dirties,
                    transactional,
                    realNativeBinaries);
        });
    }

    private static boolean hasAnnotationInHierarchy(Class<?> type, Class<? extends Annotation> annotation) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            if (current.isAnnotationPresent(annotation)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    /** Extracts cache-key differentiators beyond the base {@link SpringIntegrationTest} configuration. */
    public static List<String> inspectDifferentiators(
            Class<?> testClass,
            Set<Class<?>> configClasses,
            List<String> propertySourceProperties,
            List<String> propertySourceLocations,
            String[] activeProfiles,
            Set<Class<?>> baseImportClasses) {
        List<String> diffs = new ArrayList<>();
        if (testClass != null) {
            Class<?> cur = testClass;
            while (cur != null && cur != Object.class) {
                for (var field : cur.getDeclaredFields()) {
                    if (field.isAnnotationPresent(MockitoBean.class)) {
                        diffs.add("@MockitoBean(" + field.getType().getSimpleName() + ")");
                    }
                    if (field.isAnnotationPresent(MockitoSpyBean.class)) {
                        diffs.add("@MockitoSpyBean(" + field.getType().getSimpleName() + ")");
                    }
                }
                if (cur.isAnnotationPresent(DirtiesContext.class)) {
                    DirtiesContext dc = cur.getAnnotation(DirtiesContext.class);
                    diffs.add("@DirtiesContext(" + dc.classMode() + ")");
                }
                if (cur.isAnnotationPresent(Import.class) && cur != SpringIntegrationTest.class) {
                    for (Class<?> imp : cur.getAnnotation(Import.class).value()) {
                        diffs.add("@Import(" + imp.getSimpleName() + ")");
                    }
                }
                if (cur.isAnnotationPresent(TestPropertySource.class)) {
                    TestPropertySource tps = cur.getAnnotation(TestPropertySource.class);
                    if (tps.properties().length > 0) {
                        diffs.add("@TestPropertySource(" + String.join(",", tps.properties()) + ")");
                    }
                    if (tps.locations().length > 0) {
                        diffs.add("@TestPropertySource(locations=" + String.join(",", tps.locations()) + ")");
                    }
                }
                cur = cur.getSuperclass();
            }
        }
        if (configClasses != null && baseImportClasses != null) {
            for (Class<?> cls : configClasses) {
                if (!baseImportClasses.contains(cls)
                        && (testClass == null || cls != testClass)
                        && !cls.getName().equals("com.kratisai.controlplane.KratisApplication")
                        && !cls.getName().equals("com.kratisai.controlplane.KratisControlPlaneApplication")) {
                    String tag = "configClass:" + cls.getSimpleName();
                    if (!diffs.contains("@Import(" + cls.getSimpleName() + ")") && !diffs.contains(tag)) {
                        diffs.add(tag);
                    }
                }
            }
        }
        if (propertySourceProperties != null && !propertySourceProperties.isEmpty()) {
            for (String p : propertySourceProperties) {
                if ("server.port=0".equals(p)
                        || "org.springframework.boot.test.context.SpringBootTestContextBootstrapper=true".equals(p)) {
                    continue;
                }
                String tag = "prop:" + p;
                if (diffs.stream().noneMatch(d -> d.contains(p))) {
                    diffs.add(tag);
                }
            }
        }
        if (propertySourceLocations != null && !propertySourceLocations.isEmpty()) {
            for (String loc : propertySourceLocations) {
                diffs.add("propLoc:" + loc);
            }
        }
        if (activeProfiles != null) {
            for (String profile : activeProfiles) {
                if (!"test".equals(profile)) {
                    diffs.add("profile:" + profile);
                }
            }
        }
        return diffs.stream().distinct().toList();
    }

    private static ConcurrencyStats computeConcurrencyStats(List<ClassTiming> classes, long suiteWallNanos) {
        List<long[]> events = new ArrayList<>(classes.size() * 2);
        for (ClassTiming c : classes) {
            long start = c.startOffsetNanos.get();
            long end = c.endOffsetNanos.get();
            if (start >= 0L && end >= start) {
                events.add(new long[] {start, 1L});
                events.add(new long[] {end, -1L});
            }
        }
        events.sort(Comparator.comparingLong((long[] a) -> a[0]).thenComparingLong(a -> a[1]));

        Map<Integer, Long> nanosByConcurrency = new TreeMap<>();
        int active = 0;
        long prevTime = 0L;
        int peak = 0;
        long latestMultiEnd = 0L;

        for (long[] ev : events) {
            long t = Math.min(ev[0], suiteWallNanos);
            if (t > prevTime) {
                nanosByConcurrency.merge(active, t - prevTime, Long::sum);
                if (active >= 2) {
                    latestMultiEnd = t;
                }
            }
            active += (int) ev[1];
            if (active > peak) {
                peak = active;
            }
            prevTime = t;
        }
        if (suiteWallNanos > prevTime) {
            nanosByConcurrency.merge(active, suiteWallNanos - prevTime, Long::sum);
        }
        long fourPlusNanos = nanosByConcurrency.entrySet().stream()
                .filter(e -> e.getKey() >= 4)
                .mapToLong(Map.Entry::getValue)
                .sum();

        long tailNanos = Math.max(0L, suiteWallNanos - latestMultiEnd);
        long finalMultiEnd = latestMultiEnd;
        List<String> tailClasses = classes.stream()
                .filter(c -> c.endOffsetNanos.get() > finalMultiEnd)
                .sorted(Comparator.comparingLong(c -> c.startOffsetNanos.get()))
                .map(c -> String.format(
                        Locale.ROOT,
                        "%s(%.2fs [%.2fs..%.2fs])",
                        c.simpleName,
                        toSec(c.wallNanos.get()),
                        toSec(Math.max(0L, c.startOffsetNanos.get())),
                        toSec(Math.max(0L, c.endOffsetNanos.get()))))
                .toList();

        return new ConcurrencyStats(peak, nanosByConcurrency, fourPlusNanos, finalMultiEnd, tailNanos, tailClasses);
    }

    private static List<WorkerThreadStats> computeWorkerThreadStats(List<ClassTiming> classes, long suiteWallNanos) {
        Map<String, List<ClassTiming>> byThread = new TreeMap<>();
        for (ClassTiming c : classes) {
            if (c.threadName != null && c.startOffsetNanos.get() >= 0L && c.endOffsetNanos.get() >= 0L) {
                byThread.computeIfAbsent(c.threadName, k -> new ArrayList<>()).add(c);
            }
        }
        List<WorkerThreadStats> result = new ArrayList<>();
        for (Map.Entry<String, List<ClassTiming>> entry : byThread.entrySet()) {
            List<ClassTiming> list = entry.getValue();
            list.sort(Comparator.comparingLong(c -> c.startOffsetNanos.get()));
            long firstStart = list.getFirst().startOffsetNanos.get();
            long lastEnd =
                    list.stream().mapToLong(c -> c.endOffsetNanos.get()).max().orElse(0L);
            long busy = 0L;
            long gap = 0L;
            long prevEnd = firstStart;
            for (ClassTiming c : list) {
                long s = c.startOffsetNanos.get();
                long e = c.endOffsetNanos.get();
                busy += Math.max(0L, e - s);
                if (s > prevEnd) {
                    gap += s - prevEnd;
                }
                if (e > prevEnd) {
                    prevEnd = e;
                }
            }
            long tailIdle = Math.max(0L, suiteWallNanos - lastEnd);
            result.add(new WorkerThreadStats(entry.getKey(), list.size(), busy, gap, firstStart, lastEnd, tailIdle));
        }
        result.sort(
                Comparator.comparingLong((WorkerThreadStats w) -> w.busyNanos).reversed());
        return result;
    }

    private static List<SlotStats> computeSlotStats(List<ClassTiming> classes, Iterable<ContextTrace> contexts) {
        Map<Integer, List<ClassTiming>> classesBySlot = new TreeMap<>();
        for (ClassTiming c : classes) {
            classesBySlot.computeIfAbsent(c.slot, k -> new ArrayList<>()).add(c);
        }
        Map<Integer, List<ContextTrace>> ctxBySlot = new TreeMap<>();
        for (ContextTrace ct : contexts) {
            ctxBySlot.computeIfAbsent(ct.slot, k -> new ArrayList<>()).add(ct);
        }
        Set<Integer> allSlots = new LinkedHashSet<>(classesBySlot.keySet());
        allSlots.addAll(ctxBySlot.keySet());

        List<SlotStats> out = new ArrayList<>();
        for (int slot : allSlots) {
            List<ClassTiming> cls = classesBySlot.getOrDefault(slot, List.of());
            List<ContextTrace> ctxs = ctxBySlot.getOrDefault(slot, List.of());
            String label = slot == SLOT_NONE ? "NONE" : "SLOT_" + slot;
            String modeSummary = cls.stream()
                    .map(c -> !c.slotGroup.isEmpty() ? c.slotGroup : c.slotMode)
                    .distinct()
                    .reduce((a, b) -> a + "+" + b)
                    .orElse("UNIT");
            int tests = cls.stream().mapToInt(c -> (int) c.testCount.sum()).sum();
            long busy = cls.stream().mapToLong(c -> c.wallNanos.get()).sum();
            long minStart = cls.stream()
                    .mapToLong(c -> c.startOffsetNanos.get())
                    .filter(v -> v >= 0L)
                    .min()
                    .orElse(0L);
            long maxEnd = cls.stream()
                    .mapToLong(c -> c.endOffsetNanos.get())
                    .filter(v -> v >= 0L)
                    .max()
                    .orElse(0L);
            long span = Math.max(0L, maxEnd - minStart);
            int hits = ctxs.stream().mapToInt(c -> (int) c.cacheHits.sum()).sum();
            long ctxRefresh = ctxs.stream()
                    .mapToLong(c -> Math.max(0L, c.refreshDurationNanos.get()))
                    .sum();
            long dbClean = cls.stream().mapToLong(c -> c.dbCleanNanos.sum()).sum();
            long pureTest = cls.stream()
                    .mapToLong(c -> Math.max(0L, c.testBodyNanos.sum() - c.drainAfterTestExecNanos.sum()))
                    .sum();
            out.add(new SlotStats(
                    slot,
                    label,
                    modeSummary,
                    cls.size(),
                    tests,
                    busy,
                    span,
                    ctxs.size(),
                    hits,
                    ctxRefresh,
                    dbClean,
                    pureTest));
        }
        out.sort(Comparator.comparingInt(SlotStats::slot));
        return out;
    }

    static String categorizeCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "unknown";
        }
        Path fileName = Path.of(command.getFirst()).getFileName();
        String exe = fileName != null ? fileName.toString() : command.getFirst();
        if ("docker".equals(exe) && command.size() > 1) {
            return "docker " + command.get(1);
        }
        return exe;
    }

    private void writeSummaryFile(String summary, Path path) {
        try {
            ensureParentDir(path);
            Files.writeString(path, summary, StandardCharsets.UTF_8);
            logger.info("Test timing summary written to {}", path);
        } catch (IOException e) {
            logger.warn("Failed to write timing summary to {}: {}", path, e.getMessage());
        }
    }

    private void writeMainCsv(ReportSnapshot s, Path path) {
        try {
            ensureParentDir(path);
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                w.write(
                        "type,name,tests,total_ms,test_ms,setup_ms,slot,slot_group,thread,start_ms,end_ms,before_all_ms,prepare_ms,before_each_ms,after_each_ms,after_all_ms,db_clean_ms,drain_ms,process_ms,context_id,schema,cache_hit,flags");
                w.newLine();
                for (ClassTiming c : s.classes) {
                    long totalMs = toMs(c.wallNanos.get());
                    long pureTestMs = toMs(Math.max(0L, c.testBodyNanos.sum() - c.drainAfterTestExecNanos.sum()));
                    long setupMs = Math.max(0L, totalMs - pureTestMs);
                    w.write(String.format(
                            Locale.ROOT,
                            "CLASS,%s,%d,%d,%d,%d,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%s,%s",
                            csvEscape(c.simpleName),
                            c.testCount.sum(),
                            totalMs,
                            pureTestMs,
                            setupMs,
                            c.slot == SLOT_NONE ? "NONE" : "SLOT_" + c.slot,
                            csvEscape(c.slotGroup.isEmpty() ? c.slotMode : c.slotGroup),
                            csvEscape(c.threadName == null ? "" : c.threadName),
                            toMs(Math.max(0L, c.startOffsetNanos.get())),
                            toMs(Math.max(0L, c.endOffsetNanos.get())),
                            toMs(c.beforeAllNanos.sum()),
                            toMs(c.prepareInstanceNanos.sum()),
                            toMs(c.beforeEachNanos.sum()),
                            toMs(c.afterEachNanos.sum()),
                            toMs(c.afterAllNanos.sum()),
                            toMs(c.dbCleanNanos.sum()),
                            toMs(c.drainNanos.sum()),
                            toMs(c.processNanos.sum()),
                            csvEscape(c.contextId == null ? "" : c.contextId),
                            csvEscape(c.schema == null ? "" : c.schema),
                            c.contextId == null ? "" : Boolean.toString(c.cacheHit),
                            csvEscape(buildClassFlags(c))));
                    w.newLine();
                }
                for (MethodTiming m : s.methods) {
                    long totalMs = toMs(m.totalNanos.sum());
                    w.write(String.format(
                            Locale.ROOT,
                            "TEST,%s#%s,%d,%d,%d,0,,,,,,,,,,,,,,,,,",
                            csvEscape(simpleName(m.className)),
                            csvEscape(m.methodName),
                            m.invocations.sum(),
                            totalMs,
                            totalMs));
                    w.newLine();
                }
                w.write(String.format(
                        Locale.ROOT,
                        "SUITE,WALL_CLOCK,%d,%d,0,0,,,,,,,,,,,,,,,,,",
                        s.classes.stream().mapToLong(c -> c.testCount.sum()).sum(),
                        toMs(s.suiteWallNanos)));
                w.newLine();
            }
            logger.info("Test timing CSV written to {}", path);
        } catch (IOException e) {
            logger.warn("Failed to write timing CSV to {}: {}", path, e.getMessage());
        }
    }

    private void writeContextsCsv(ReportSnapshot s, Path path) {
        try {
            ensureParentDir(path);
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                w.write(
                        "ctx_id,slot,slot_mode,schema,init_thread,trigger_class,init_offset_ms,lock_wait_ms,refresh_ms,ready_ms,schema_create_ms,liquibase_ms,jpa_emf_ms,closed_early,closed_offset_ms,user_classes,cache_hits,differentiators");
                w.newLine();
                for (ContextTrace c : s.contexts) {
                    w.write(String.format(
                            Locale.ROOT,
                            "%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%s,%d,%d,%d,%s",
                            csvEscape(c.ctxId),
                            c.slot == SLOT_NONE ? "NONE" : "SLOT_" + c.slot,
                            csvEscape(c.slotMode == null ? "" : c.slotMode),
                            csvEscape(c.schema == null ? "" : c.schema),
                            csvEscape(c.initThread == null ? "" : c.initThread),
                            csvEscape(c.triggerClass == null ? "" : c.triggerClass),
                            toMs(c.initOffsetNanos),
                            toMs(Math.max(0L, c.lockWaitBeforeInitNanos.get())),
                            toMs(Math.max(0L, c.refreshDurationNanos.get())),
                            toMs(Math.max(0L, c.readyDurationNanos.get())),
                            toMs(c.schemaCreateNanos.sum()),
                            toMs(c.liquibaseNanos.sum()),
                            toMs(c.entityManagerFactoryNanos.sum()),
                            c.closedDuringRun,
                            toMs(Math.max(0L, c.closedOffsetNanos.get())),
                            c.userClasses.size(),
                            c.cacheHits.sum(),
                            csvEscape(String.join("; ", c.differentiators))));
                    w.newLine();
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to write contexts CSV to {}: {}", path, e.getMessage());
        }
    }

    private void writeTimelineCsv(ReportSnapshot s, Path path) {
        try {
            ensureParentDir(path);
            try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                w.write("start_ms,end_ms,duration_ms,thread,slot,slot_group,class_fqcn,ctx_id,cache_hit,failed");
                w.newLine();
                for (ClassTiming c : s.classes) {
                    w.write(String.format(
                            Locale.ROOT,
                            "%d,%d,%d,%s,%s,%s,%s,%s,%s,%s",
                            toMs(Math.max(0L, c.startOffsetNanos.get())),
                            toMs(Math.max(0L, c.endOffsetNanos.get())),
                            toMs(c.wallNanos.get()),
                            csvEscape(c.threadName == null ? "" : c.threadName),
                            c.slot == SLOT_NONE ? "NONE" : "SLOT_" + c.slot,
                            csvEscape(c.slotGroup.isEmpty() ? c.slotMode : c.slotGroup),
                            csvEscape(c.fqcn),
                            csvEscape(c.contextId == null ? "" : c.contextId),
                            c.contextId == null ? "" : Boolean.toString(c.cacheHit),
                            c.failedClass || c.failedTestCount.sum() > 0L));
                    w.newLine();
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to write timeline CSV to {}: {}", path, e.getMessage());
        }
    }

    private static String buildClassFlags(ClassTiming c) {
        List<String> flags = new ArrayList<>();
        if (c.dirtiesContext) {
            flags.add("DirtiesContext");
        }
        if (c.transactional) {
            flags.add("Transactional");
        }
        return String.join("|", flags);
    }

    private static void ensureParentDir(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String csvEscape(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
            return "\"" + raw.replace("\"", "\"\"") + "\"";
        }
        return raw;
    }

    private static void updateMax(AtomicLong max, long value) {
        max.accumulateAndGet(value, Math::max);
    }

    private static long toMs(long nanos) {
        return nanos / NANOS_PER_MS;
    }

    private static double toSec(long nanos) {
        return nanos / NANOS_PER_SEC;
    }

    private static double pct(long part, long total) {
        return total > 0L ? (100.0 * part) / total : 0.0;
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) {
            return "?";
        }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }

    private static final class IdentityKey {
        private final Object target;
        private final int hash;

        private IdentityKey(Object target) {
            this.target = target;
            this.hash = System.identityHashCode(target);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey ik && this.target == ik.target;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /** Static container setup breakdown captured once per JVM from {@link PostgresTestInitializer}. */
    public record ContainerBootstrapStats(
            String triggeredByThread,
            String triggeredByClass,
            long totalNanos,
            long networkNanos,
            long postgresStartNanos,
            long postgresInitNanos,
            long litellmStartNanos,
            long patchVerifyNanos,
            long modelCountCheckNanos) {}

    /** Lifecycle trace for a single Spring {@code ApplicationContext}. */
    public static final class ContextTrace {
        final String ctxId;
        final long initStartNanos;
        final long initOffsetNanos;
        final String initThread;
        volatile String triggerClass;
        volatile int slot = SLOT_NONE;
        volatile String slotMode = "UNKNOWN";
        volatile String schema;
        final Set<String> differentiators = ConcurrentHashMap.newKeySet();
        final Set<String> userClasses = ConcurrentHashMap.newKeySet();
        final LongAdder cacheHits = new LongAdder();
        final LongAdder schemaCreateNanos = new LongAdder();
        final LongAdder liquibaseNanos = new LongAdder();
        final LongAdder entityManagerFactoryNanos = new LongAdder();
        final AtomicLong lockWaitBeforeInitNanos = new AtomicLong(-1L);
        final AtomicLong refreshDurationNanos = new AtomicLong(-1L);
        final AtomicLong readyDurationNanos = new AtomicLong(-1L);
        final AtomicLong closedOffsetNanos = new AtomicLong(-1L);
        volatile boolean closedDuringRun;

        ContextTrace(String ctxId, long initStartNanos, long initOffsetNanos, String initThread, String triggerClass) {
            this.ctxId = ctxId;
            this.initStartNanos = initStartNanos;
            this.initOffsetNanos = initOffsetNanos;
            this.initThread = initThread;
            this.triggerClass = triggerClass;
        }
    }

    /** Execution timing and phase breakdown for a single test class. */
    public static final class ClassTiming {
        final String fqcn;
        final String simpleName;
        final boolean springIntegrationTest;
        final boolean slowTest;
        final String slotGroup;
        final int slot;
        final String slotMode;
        final boolean dirtiesContext;
        final boolean transactional;
        final boolean usesRealNativeBinaries;

        volatile String threadName;
        final AtomicLong startOffsetNanos = new AtomicLong(-1L);
        final AtomicLong endOffsetNanos = new AtomicLong(-1L);
        final AtomicLong wallNanos = new AtomicLong(0L);
        volatile boolean failedClass;

        final LongAdder testCount = new LongAdder();
        final LongAdder failedTestCount = new LongAdder();
        final LongAdder beforeAllNanos = new LongAdder();
        final AtomicLong firstPrepareStartNanos = new AtomicLong(-1L);
        final LongAdder prepareInstanceNanos = new LongAdder();
        final LongAdder beforeEachNanos = new LongAdder();
        final LongAdder testBodyNanos = new LongAdder();
        final LongAdder afterEachNanos = new LongAdder();
        final LongAdder afterAllNanos = new LongAdder();

        final LongAdder contextAcquireNanos = new LongAdder();
        final Set<String> recordedContextIds = ConcurrentHashMap.newKeySet();
        volatile String contextId;
        volatile String schema;
        volatile boolean cacheHit;

        final LongAdder dbCleanCalls = new LongAdder();
        final LongAdder dbCleanNanos = new LongAdder();
        final LongAdder dbCleanLitellmNanos = new LongAdder();
        final LongAdder dbCleanTruncateNanos = new LongAdder();
        final LongAdder dbCleanLockHeals = new LongAdder();

        final LongAdder drainCalls = new LongAdder();
        final LongAdder drainNanos = new LongAdder();
        final LongAdder drainIdleCalls = new LongAdder();
        final LongAdder drainIdleNanos = new LongAdder();
        final LongAdder drainAfterTestExecNanos = new LongAdder();
        final LongAdder drainWaitEvents = new LongAdder();

        final LongAdder processCalls = new LongAdder();
        final LongAdder processNanos = new LongAdder();
        final LongAdder realParserCommands = new LongAdder();
        final Set<String> realParserNames = ConcurrentHashMap.newKeySet();
        final Map<String, LongAdder> subPhases = new ConcurrentHashMap<>();

        ClassTiming(
                String fqcn,
                String simpleName,
                boolean springIntegrationTest,
                boolean slowTest,
                String slotGroup,
                int slot,
                String slotMode,
                boolean dirtiesContext,
                boolean transactional,
                boolean usesRealNativeBinaries) {
            this.fqcn = fqcn;
            this.simpleName = simpleName;
            this.springIntegrationTest = springIntegrationTest;
            this.slowTest = slowTest;
            this.slotGroup = slotGroup != null ? slotGroup : "";
            this.slot = slot;
            this.slotMode = slotMode;
            this.dirtiesContext = dirtiesContext;
            this.transactional = transactional;
            this.usesRealNativeBinaries = usesRealNativeBinaries;
        }
    }

    /** Execution timing aggregate for an individual test method. */
    public static final class MethodTiming {
        final String className;
        final String methodName;
        final LongAdder invocations = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong(0L);
        final LongAdder failures = new LongAdder();

        MethodTiming(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }
    }

    /** Subprocess execution statistics grouped by command prefix. */
    public static final class ProcessCategoryStats {
        final String category;
        final LongAdder calls = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong(0L);
        final LongAdder nonZeroExits = new LongAdder();

        ProcessCategoryStats(String category) {
            this.category = category;
        }
    }

    /** Concurrency distribution and single-thread tail analysis over the test plan timeline. */
    public record ConcurrencyStats(
            int peakConcurrency,
            Map<Integer, Long> nanosByConcurrency,
            long fourPlusNanos,
            long tailStartOffsetNanos,
            long tailNanos,
            List<String> tailClasses) {}

    /** Per-worker-thread utilization summary. */
    public record WorkerThreadStats(
            String threadName,
            int classCount,
            long busyNanos,
            long interClassGapNanos,
            long firstStartOffsetNanos,
            long lastEndOffsetNanos,
            long tailIdleNanos) {}

    /** Per-slot summary combining class execution and context caching stats. */
    public record SlotStats(
            int slot,
            String slotLabel,
            String modeSummary,
            int classCount,
            int testCount,
            long busyNanos,
            long spanNanos,
            int contextsCreated,
            int cacheHits,
            long contextRefreshNanos,
            long dbCleanNanos,
            long pureTestNanos) {}

    /** Immutable snapshot of all collected metrics for report rendering and assertions. */
    public record ReportSnapshot(
            long suiteWallNanos,
            ContainerBootstrapStats containerBootstrap,
            long preSuiteDockerCleanupNanos,
            long postSuiteDockerCleanupNanos,
            List<ClassTiming> classes,
            List<MethodTiming> methods,
            List<ContextTrace> contexts,
            List<WorkerThreadStats> workers,
            List<SlotStats> slots,
            ConcurrencyStats concurrency,
            List<ProcessCategoryStats> processCategories,
            long dbCleanCalls,
            long dbCleanTotalNanos,
            long dbCleanLitellmNanos,
            long dbCleanDiscoveryNanos,
            long dbCleanTruncateNanos,
            long dbCleanLockHeals,
            long dbCleanMaxNanos,
            long drainCalls,
            long drainTotalNanos,
            long drainIdleCalls,
            long drainIdleNanos,
            long drainBusyCalls,
            long drainBusyNanos,
            long drainWaitEvents,
            long drainMaxNanos,
            Map<String, LongAdder> drainWaitedBeans,
            long chatModelResetCalls,
            long chatModelResetTotalNanos) {}
}
