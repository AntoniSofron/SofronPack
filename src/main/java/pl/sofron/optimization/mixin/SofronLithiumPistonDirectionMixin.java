/*
 * Adapted from Lithium for Minecraft 26.3 (JellySquid, 2No2Name and
 * Lithium contributors): https://github.com/CaffeineMC/lithium
 * Licensed under LGPL-3.0-only. See THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Avoid allocating a direction array on every piston neighbour check. */
@Mixin(PistonBaseBlock.class)
public abstract class SofronLithiumPistonDirectionMixin {
    private static final Direction[] DIRECTIONS = Direction.values();

    @Redirect(method = "getNeighborSignal",
            at = @At(value = "INVOKE", target =
                "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private Direction[] sofron$reuseDirections() {
        return DIRECTIONS;
    }
}
