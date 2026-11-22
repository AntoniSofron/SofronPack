/*
 * Adapted from ModernFix 5.28 (Minecraft 1.20.1 source archive),
 * originally by embeddedt / ModernFix contributors.
 * Algorithm re-targeted to Minecraft 26.3; see THIRD_PARTY_OPTIMIZATIONS.md.
 */
package pl.sofron.optimization.modernfix;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SurfaceRules.SequenceRule.class)
public abstract class SofronSurfaceRuleSequenceMixin {
    @Shadow @Final private List<SurfaceRules.SurfaceRule> rules;

    /**
     * @author embeddedt / ModernFix contributors
     * @reason Avoid a repeatedly allocated iterator in the hot terrain-surface rule loop.
     */
    @Overwrite
    public BlockState tryApply(int x, int y, int z) {
        List<SurfaceRules.SurfaceRule> rules = this.rules;
        for (int i = 0, count = rules.size(); i < count; i++) {
            BlockState state = rules.get(i).tryApply(x, y, z);
            if (state != null) return state;
        }
        return null;
    }
}
