/*
 * Adapted from Lithium 0.26.4 for Minecraft 26.3.
 * Copyright Lithium contributors (SuperCoder79, 2No2Name).
 * LGPL-3.0-only. https://github.com/CaffeineMC/lithium
 */
package pl.sofron.optimization.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * World generation repeatedly queries sea level for individual block
 * placements. Cache immutable noise settings once per generator instance,
 * lazily to avoid accessing unbound registry holders in constructors.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class SofronLithiumCachedSeaLevelMixin {
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;
    @Unique private int sofron$cachedSeaLevel = Integer.MIN_VALUE;

    /**
     * @author SuperCoder79, 2No2Name
     * @reason Remove repeated registry indirection in the chunk generation hot path.
     */
    @Overwrite
    public int getSeaLevel() {
        if (sofron$cachedSeaLevel == Integer.MIN_VALUE)
            sofron$cachedSeaLevel = settings.value().seaLevel();
        return sofron$cachedSeaLevel;
    }
}
