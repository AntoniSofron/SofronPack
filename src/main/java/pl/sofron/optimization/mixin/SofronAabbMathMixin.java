/* SofronPack optimization component. Licensing and contributor notices:
 * LEGAL_NOTICE.md (distributed with this source).
 */
package pl.sofron.optimization.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Fast axis-selection in collision and entity movement hot paths. */
@Mixin(AABB.class)
public abstract class SofronAabbMathMixin {
    @Shadow @Final public double minX;
    @Shadow @Final public double minY;
    @Shadow @Final public double minZ;
    @Shadow @Final public double maxX;
    @Shadow @Final public double maxY;
    @Shadow @Final public double maxZ;

    /** @author SofronPack integration (see LEGAL_NOTICE.md)
    @Overwrite
    public double min(Direction.Axis axis) {
        return switch (axis) {
            case X -> minX;
            case Y -> minY;
            case Z -> minZ;
        };
    }

    /** @author SofronPack integration (see LEGAL_NOTICE.md)
    @Overwrite
    public double max(Direction.Axis axis) {
        return switch (axis) {
            case X -> maxX;
            case Y -> maxY;
            case Z -> maxZ;
        };
    }
}
