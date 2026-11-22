/*
 * Memory deduplication adapted from ModernFix 5.28's compact Mojang registry
 * and blockstate data interning techniques (embeddedt and contributors).
 * Unlike the Forge 1.20.1 mixins, this targets SofronPack's immutable 26.3
 * voxel snapshot palette and never mutates Minecraft registry internals.
 * Refer to THIRD_PARTY_OPTIMIZATIONS.md for authorship and source.
 */
package pl.sofron.optimization.modernfix;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

/**
 * Immutable material-state names repeat in thousands of distant chunks.
 * Canonicalizing just the String objects (not mutable palette arrays)
 * reduces the 128-chunk LOD archive's live Java heap usage. Guava's weak
 * interner prevents keeping names alive after worlds are unloaded.
 */
public final class SofronMaterialNameInterner {
    private static final Interner<String> NAMES = Interners.newWeakInterner();

    private SofronMaterialNameInterner() {}

    public static String canonicalize(String name) {
        if (name == null) throw new IllegalArgumentException("Null material id");
        return NAMES.intern(name);
    }
}
