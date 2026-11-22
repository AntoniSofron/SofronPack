/*
 * Adapted from Lithium for Minecraft 26.3 (JellySquid, 2No2Name and
 * Lithium contributors): https://github.com/CaffeineMC/lithium
 * Licensed under LGPL-3.0-only. See THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.mixin;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Avoid per-call Direction.values() array allocations and opposite modulo. */
@Mixin(Direction.class)
public abstract class SofronLithiumDirectionMixin {
    @Shadow @Final private static Direction[] VALUES;
    @Shadow @Final private int oppositeIndex;

    /** @author JellySquid @reason Avoid modulo and abs on the hot block update path. */
    @Overwrite
    public Direction getOpposite() {
        return VALUES[oppositeIndex];
    }

    /** @author JellySquid @reason Reuse the internal direction constant array. */
    @Overwrite
    public static Direction getRandom(RandomSource random) {
        return VALUES[random.nextInt(VALUES.length)];
    }
}
