/* SofronPack optimization component. Licensing and contributor notices:
 * LEGAL_NOTICE.md (distributed with this source).
 */
package pl.sofron.optimization.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PistonStructureResolver.class)
public abstract class SofronPistonResolverMixin {
    private static final Direction[] DIRECTIONS = Direction.values();
    @Redirect(method = "addBranchingBlocks(Lnet/minecraft/core/BlockPos;)Z",
            at = @At(value = "INVOKE", target =
                "Lnet/minecraft/core/Direction;values()[Lnet/minecraft/core/Direction;"))
    private Direction[] sofron$reuseDirections() {
        return DIRECTIONS;
    }
}
