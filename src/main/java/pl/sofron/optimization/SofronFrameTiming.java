package pl.sofron.optimization;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead timing breadcrumbs for freeze reports.
 * Only unusually long SofronPack work is logged, at most once per stage
 * per 15 seconds. The absence of a message does not exclude a JVM GC pause
 * or disk stalls inside vanilla Minecraft.
 */
public final class SofronFrameTiming {
    private static final long REPORT_INTERVAL_NS = 15_000_000_000L;
    private static final ConcurrentHashMap<String, AtomicLong> LAST = new ConcurrentHashMap<>();

    private SofronFrameTiming() {}

    public static void reportIfSlow(String stage, long startNs, long thresholdNs) {
        long now = System.nanoTime();
        long elapsed = now - startNs;
        if (elapsed < thresholdNs) return;
        AtomicLong last = LAST.computeIfAbsent(stage, ignored -> new AtomicLong());
        long previous = last.get();
        if (previous != 0 && now - previous < REPORT_INTERVAL_NS) return;
        if (last.compareAndSet(previous, now)) {
            System.err.println("[SofronPack] Frame-time diagnostic: " + stage
                    + " took " + (elapsed / 1_000_000.0) + " ms");
        }
    }
}
