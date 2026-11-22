package pl.sofron.optimization.memory;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

/**
 * Immutable material-state names repeat in thousands of distant chunks.
 * Canonicalizing just the String objects (not mutable palette arrays)
 * reduces the 128-chunk LOD archive's live Java heap usage. Guava's weak
 * interner prevents keeping names alive after worlds are unloaded.
 */
public final class SofronVoxelPalettePool {
    private static final Interner<String> NAMES = Interners.newWeakInterner();

    private SofronVoxelPalettePool() {}

    public static String canonicalize(String name) {
        if (name == null) throw new IllegalArgumentException("Null material id");
        return NAMES.intern(name);
    }
}
