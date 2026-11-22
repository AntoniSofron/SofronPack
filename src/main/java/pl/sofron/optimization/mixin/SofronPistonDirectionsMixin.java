/* SofronPack optimization component. Licensing and contributor notices:
 * LEGAL_NOTICE.md (distributed with this source).
 */
package pl.sofron.optimization.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Avoid allocating a direction array on every piston neighbour check. */
@Mixin(PistonBaseBlock.class)
public abstract class SofronPistonDirectionsMixin {
    private static final Direction[] DIRECTIONS = Direction.values();

    @Redirect(method = "getNeighborSignal",
            at = @At(value = "INVOKE", target =
                "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private Direction[] sofron$reuseDirections() {
        return DIRECTIONS;
    }
}
