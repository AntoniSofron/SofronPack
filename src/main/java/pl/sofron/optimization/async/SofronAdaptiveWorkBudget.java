/*
 * Adapted from Async 0.2.6+alpha-26.3 by AxalotL, Alchemy, Bliss,
 * FurryMileon, Grider and jediminer543, specifically
 * ParallelProcessor.CostModel's thread-safe moving-average task-cost model.
 * No off-thread world/entity mutation is permitted. See provenance notes.
 */
package pl.sofron.optimization.async;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe cost estimator for short, isolated synchronous chunk snapshots.
 * A 25% exponentially weighted moving average allows the queue to use spare
 * server time without scheduling unbounded work on a busy integrated server.
 * Pure Java; independent of OpenGL, Vulkan or any Minecraft mixin.
 */
public final class SofronAdaptiveWorkBudget {
    private static final double SMOOTHING = 0.25;
    private static final long MIN_SAMPLE_NS = 10_000;
    private static final long MAX_SAMPLE_NS = 50_000_000;

    private final LongAdder totalNanos = new LongAdder();
    private final LongAdder sampleCount = new LongAdder();
    private volatile double nanosPerItem;

    public SofronAdaptiveWorkBudget(double initialNanosPerItem) {
        if (!Double.isFinite(initialNanosPerItem) || initialNanosPerItem <= 0)
            throw new IllegalArgumentException("Invalid initial task time");
        nanosPerItem = initialNanosPerItem;
    }

    /** Must be called after a task, even if it encounters an exception. */
    public void sample(long elapsedNanos) {
        totalNanos.add(Math.clamp(elapsedNanos, MIN_SAMPLE_NS, MAX_SAMPLE_NS));
        sampleCount.increment();
    }

    /**
     * Returns a safe count within [1, maxItems]. Intentionally never exceeds
     * the original per-tick upper bound even on a very fast CPU.
     */
    public synchronized int budget(int maxItems, long targetNanos) {
        if (maxItems <= 0 || targetNanos <= 0) throw new IllegalArgumentException("Invalid limits");
        long samples = sampleCount.sumThenReset();
        long elapsed = totalNanos.sumThenReset();
        if (samples > 0) {
            double observed = (double) elapsed / samples;
            nanosPerItem += (observed - nanosPerItem) * SMOOTHING;
        }
        return (int) Math.clamp((long) (targetNanos / nanosPerItem), 1, maxItems);
    }

    public double estimatedNanosPerItem() { return nanosPerItem; }
}
