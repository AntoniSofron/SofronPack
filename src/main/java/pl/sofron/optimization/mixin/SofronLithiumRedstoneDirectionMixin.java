/*
 * Adapted from Lithium for Minecraft 26.3 (JellySquid, 2No2Name and
 * Lithium contributors): https://github.com/CaffeineMC/lithium
 * Licensed under LGPL-3.0-only. See THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RedstoneWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RedstoneWireBlock.class)
public abstract class SofronLithiumRedstoneDirectionMixin {
    private static final Direction[] DIRECTIONS = Direction.values();

    @Redirect(method = "affectNeighborsAfterRemoval", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private Direction[] sofron$reuseDirectionsOnRemoval() {
        return DIRECTIONS;
    }

    @Redirect(method = "checkCornerChangeAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
            at = @At(value = "INVOKE",
                target = "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private Direction[] sofron$reuseDirectionsOnCornerChange() {
        return DIRECTIONS;
    }
}
