package com.kratisai.controlplane;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SlowTestRegistry {
    private static final ConcurrentHashMap<Class<?>, AtomicLong> pureExecutionTimes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Boolean> failedClasses = new ConcurrentHashMap<>();

    public static void markFailed(Class<?> testClass) {
        failedClasses.put(testClass, true);
    }

    public static boolean hasFailed(Class<?> testClass) {
        return failedClasses.getOrDefault(testClass, false);
    }

    public static void addTime(Class<?> testClass, long durationMs) {
        pureExecutionTimes.computeIfAbsent(testClass, k -> new AtomicLong(0)).addAndGet(durationMs);
    }

    public static long getPureExecutionTimeMs(Class<?> testClass) {
        AtomicLong time = pureExecutionTimes.get(testClass);
        return time != null ? time.get() : 0;
    }

    public static ConcurrentHashMap<Class<?>, AtomicLong> getAllTimes() {
        return pureExecutionTimes;
    }
}
