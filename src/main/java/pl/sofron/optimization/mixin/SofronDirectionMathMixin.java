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

    /** Equivalent optimized hot path. */
    @Overwrite
    public Direction getOpposite() {
        return VALUES[oppositeIndex];
    }

    /** Equivalent optimized hot path. */
    @Overwrite
    public static Direction getRandom(RandomSource random) {
        return VALUES[random.nextInt(VALUES.length)];
    }
}
