/*
 * Inspired by C2ME 26.3's ore-generation BitSet reuse.
 * Copyright (c) 2021-2026 ishland and C2ME contributors.
 * License: LGPL-3.0-only. https://github.com/RelativityMC/C2ME-fabric
 * See THIRD_PARTY_OPTIMIZATIONS.md for attribution.
 */
package pl.sofron.optimization.mixin;

import java.util.BitSet;
import net.minecraft.world.gen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import pl.sofron.optimization.chunk.SofronOreBitsetCache;

/** Reduce repeated BitSet allocations during chunk ore placement. */
@Mixin(OreFeature.class)
public abstract class SofronOreBitsetMixin {
    @Redirect(method = "generateVeinPart", at = @At(value = "NEW", target = "java/util/BitSet"))
    private BitSet sofron$pooledOreBitSet(int capacity) {
        return SofronOreBitsetCache.empty(capacity);
    }
}
