package pl.sofron.extended.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.sofron.extended.client.RemoteLodCache;

@Mixin(LevelChunk.class)
public abstract class ClientLodEditMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void sofronpack$clientEdit(BlockPos pos, BlockState state, int flags,
                                        CallbackInfoReturnable<BlockState> result) {
        if (result.getReturnValue() != null)
            RemoteLodCache.changed((LevelChunk)(Object)this);
    }
}
