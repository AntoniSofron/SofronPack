package pl.sofron.light.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.sofron.light.render.gpu.SodiumRenderPassCoordinator;

@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class MinecraftFramePrepareMixin {
    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0, require = 1)
    private org.joml.Matrix4f sofron$captureTerrainProjection(org.joml.Matrix4f matrix) {
        pl.sofron.extended.client.ExtendedWorldLodRenderer.updateProjection(matrix);
        return matrix;
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void sofron$prepareLightsBeforeFrame(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var camera = mc.gameRenderer.mainCamera();
        var pos = camera.position();
        SodiumRenderPassCoordinator.prepareFrame(pos.x, pos.y, pos.z);
        pl.sofron.extended.client.ExtendedWorldLodRenderer.prepareFrame(pos.x, pos.z);
    }
}
