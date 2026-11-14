package com.kratisai.controlplane.ingestion.surfaces;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Per-batch extraction counters. Each key names a reason, shown in batch logs. */
final class SurfaceCounters {

    private final Map<String, Integer> counts = new TreeMap<>();

    void increment(String reason) {
        add(reason, 1);
    }

    void incrementAll(SurfaceCounters other) {
        other.counts.forEach((reason, amount) -> this.counts.merge(reason, amount, Integer::sum));
    }

    void add(String reason, int amount) {
        counts.merge(reason, amount, Integer::sum);
    }

    Map<String, Integer> snapshot() {
        return Collections.unmodifiableMap(new TreeMap<>(counts));
    }
}
