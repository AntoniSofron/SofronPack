/*
 * Adapted from ModernFix 5.28 (Minecraft 1.20.1 source archive),
 * originally by embeddedt / ModernFix contributors.
 * Algorithm re-targeted to Minecraft 26.3; see THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.modernfix;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/** Preserve vanilla first-match ordering while avoiding iterator churn. */
@Mixin(MaterialRuleList.class)
public abstract class SofronMaterialRuleListMixin {
    @Shadow @Final private List<NoiseChunk.BlockStateFiller> materialRuleList;

    /**
     * @author embeddedt / ModernFix contributors
     * @reason Reduce allocations and dispatch in material-rule selection.
     */
    @Overwrite
    public BlockState calculate(DensityFunction.FunctionContext context) {
        List<NoiseChunk.BlockStateFiller> rules = materialRuleList;
        for (int i = 0, size = rules.size(); i < size; i++) {
            BlockState state = rules.get(i).calculate(context);
            if (state != null) return state;
        }
        return null;
    }
}
