package pl.sofron.extended.mixin;

import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.sofron.terralab.trees.SnowLeafTint;

@Mixin(LevelExtractor.class)
public abstract class SnowLeafRebuildMixin {
    @Shadow public abstract void setSectionDirty(int x, int y, int z);
    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("RETURN"))
    private void sofronpack$refreshSnowGradient(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if (SnowLeafTint.kind(oldState) == SnowLeafTint.kind(newState)) return;
        int section = pos.getY() >> 4;
        int lower = (pos.getY() - 8) >> 4;
        if (lower != section) setSectionDirty(pos.getX() >> 4, lower, pos.getZ() >> 4);
    }
}
