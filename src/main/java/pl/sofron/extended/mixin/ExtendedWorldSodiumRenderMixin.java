package pl.sofron.extended.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.sofron.extended.client.ExtendedWorldLodRenderer;

/** Draw our self-contained LOD tiles inside Minecraft's existing terrain pass. */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class ExtendedWorldSodiumRenderMixin {
    @org.spongepowered.asm.mixin.Unique private boolean sofron$loggedSodiumFog;

    @Inject(method = "render", at = @At("HEAD"), remap = false, require = 0)
    private void sofron$nativeLod(ChunkRenderMatrices matrices,
            ChunkRenderListIterable lists, TerrainRenderPass terrainPass,
            CameraTransform camera, FogParameters fog, boolean sort,
            RenderPass renderPass, GpuSampler sampler, GpuBufferSlice transforms,
            GpuBuffer uniform, OitStage stage, CallbackInfo ci) {
        if (!sofron$loggedSodiumFog && fog.renderEnd() == 2048f &&
                pl.sofron.extended.ExtendedWorldConfig.get().enabled) {
            sofron$loggedSodiumFog = true;
            System.out.println("[SofronPack] Sodium terrain fog range confirmed at 2048 blocks");
        }
        ExtendedWorldLodRenderer.render(renderPass, matrices, terrainPass, camera, sampler);
    }
}
