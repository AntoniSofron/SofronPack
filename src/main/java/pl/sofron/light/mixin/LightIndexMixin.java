package pl.sofron.light.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.sofron.light.SofronPackLightClient;

@Mixin(ClientLevel.class)
abstract class LightIndexMixin {
    @Inject(method = "sendBlockUpdated", at = @At("RETURN"))
    private void sofron$changed(BlockPos pos, BlockState old, BlockState state, int flags, CallbackInfo ci) {
        if (old != state) {
            // All vanilla lava levels have the same empty collision shape.
            if (!(old.is(Blocks.LAVA) && state.is(Blocks.LAVA))) {
                SofronPackLightClient.VOXELS.invalidate(pos);
                SofronPackLightClient.PROBE.invalidate(pos);
            }
            int before = old.getLightEmission(), after = state.getLightEmission();
            if (before != after || (after > 0 && old.getBlock() != state.getBlock())
                    || old.canOcclude() != state.canOcclude())
                SofronPackLightClient.LIGHTS.chunkIndex().invalidate(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }
    @Inject(method = "onChunkLoaded", at = @At("RETURN"))
    private void sofron$loaded(ChunkPos pos, CallbackInfo ci) {
        SofronPackLightClient.LIGHTS.chunkIndex().invalidate(pos.x(), pos.z());
        SofronPackLightClient.VOXELS.invalidateChunk(pos.x(),pos.z());
        SofronPackLightClient.PROBE.invalidateChunk(pos.x(),pos.z());
    }
}
