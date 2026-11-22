/* SofronPack optimization component. Licensing and contributor notices:
 * LEGAL_NOTICE.md (distributed with this source).
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
public abstract class SofronDirectionMathMixin {
    @Shadow @Final private static Direction[] VALUES;
    @Shadow @Final private int oppositeIndex;

    /** @author SofronPack integration (see LEGAL_NOTICE.md)
    @Overwrite
    public Direction getOpposite() {
        return VALUES[oppositeIndex];
    }

    /** @author SofronPack integration (see LEGAL_NOTICE.md)
    @Overwrite
    public static Direction getRandom(RandomSource random) {
        return VALUES[random.nextInt(VALUES.length)];
    }
}
