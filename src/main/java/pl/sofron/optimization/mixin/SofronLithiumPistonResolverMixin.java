/*
 * Adapted from Lithium for Minecraft 26.3 (JellySquid, 2No2Name and
 * Lithium contributors): https://github.com/CaffeineMC/lithium
 * Licensed under LGPL-3.0-only. See THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PistonStructureResolver.class)
public abstract class SofronLithiumPistonResolverMixin {
    private static final Direction[] DIRECTIONS = Direction.values();
    @Redirect(method = "addBranchingBlocks(Lnet/minecraft/core/BlockPos;)Z",
            at = @At(value = "INVOKE", target =
                "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private Direction[] sofron$reuseDirections() {
        return DIRECTIONS;
    }
}
