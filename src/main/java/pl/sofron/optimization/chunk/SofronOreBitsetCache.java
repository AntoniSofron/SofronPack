/*
 * Inspired by C2ME 26.3's ore-generation BitSet reuse.
 * Copyright (c) 2021-2026 ishland and C2ME contributors.
 * License: LGPL-3.0-only. https://github.com/RelativityMC/C2ME-fabric
 * See THIRD_PARTY_OPTIMIZATIONS.md for attribution.
 */
package pl.sofron.optimization.chunk;

import java.util.BitSet;

/**
 * Each worldgen worker borrows its own BitSet for ore-vein generation.
 * Reuses the allocation without sharing mutable state across threads.
 * The limit prevents extreme modded ores from retaining huge arrays.
 */
public final class SofronOreBitsetCache {
    private static final int MAX_RETAINED_BITS = 65536;
    private static final ThreadLocal<BitSet> SHARED = ThreadLocal.withInitial(BitSet::new);

    private SofronOreBitsetCache() {}

    public static BitSet empty(int bits) {
        if (bits < 0) throw new IllegalArgumentException("Negative BitSet capacity");
        if (bits > MAX_RETAINED_BITS) return new BitSet(bits);
        BitSet reused = SHARED.get();
        reused.clear();
        return reused;
    }
}
