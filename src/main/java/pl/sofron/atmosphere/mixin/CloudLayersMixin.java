package pl.sofron.atmosphere.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.sofron.atmosphere.CloudBandLayout;

/**
 * Three independent textured cloud volumes at Y=256, 384 and 512. All of them
 * reuse the existing pack-defined cloud texture and the current sky biome
 * sample. Auxiliary renderers never create their own children.
 */
@Mixin(CloudRenderer.class)
abstract class CloudLayersMixin {
    @Shadow private CloudRenderer.TextureData texture;
    @Shadow private int quadCount;
    @Shadow protected abstract void render(RenderPass pass, RenderPipeline pipeline);

    @Unique private CloudRenderer sofron$middle;
    @Unique private CloudRenderer sofron$high;
    @Unique private boolean sofron$isAuxiliary;
    @Unique private boolean sofron$layered;
    @Unique private float sofron$cameraY;
    @Unique private int sofron$range = -1;
    @Unique private final Vector4f sofron$sky = new Vector4f();

    @Unique private static CloudLayersMixin sofron$state(CloudRenderer renderer) {
        return (CloudLayersMixin)(Object)renderer;
    }

    @Unique private CloudRenderer sofron$createAuxiliary() {
        CloudRenderer renderer = new CloudRenderer();
        sofron$state(renderer).sofron$isAuxiliary = true;
        return renderer;
    }

    @Unique private void sofron$prepareAuxiliary(CloudRenderer renderer,
            int color, CloudStatus status, float height, int range, Vec3 camera,
            long ticks, float partial) {
        CloudLayersMixin child = sofron$state(renderer);
        if (child.texture != texture) {
            child.texture = texture;
            renderer.markForRebuild();
        }
        child.sofron$sky.set(sofron$sky);
        child.sofron$layered = true;
        renderer.prepare(color, status, height, range, camera, ticks, partial);
    }

    @Inject(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"))
    private void sofron$prepare(int color, CloudStatus status, float height, int range, Vec3 camera,
                                long ticks, float partial, CallbackInfo ci) {
        if (range != sofron$range) {
            ((CloudRenderer)(Object)this).markForRebuild();
            sofron$range = range;
        }
        if (sofron$isAuxiliary) return;
        var client = Minecraft.getInstance();
        sofron$layered = texture != null && client.level != null
                && client.level.dimension().equals(Level.OVERWORLD);
        if (!sofron$layered) return;
        if (sofron$middle == null) sofron$middle = sofron$createAuxiliary();
        if (sofron$high == null) sofron$high = sofron$createAuxiliary();

        var sky = client.gameRenderer.mainCamera().attributeProbe()
                .getValue(EnvironmentAttributes.SKY_COLOR, partial);
        // A negative alpha signals that the cloud shader should apply a subtle
        // palette-aware tint to the UNDERSIDE only, not paint the cloud like sky.
        sofron$sky.set(sky, -1f);
        sofron$cameraY = (float) camera.y;
        sofron$prepareAuxiliary(sofron$middle, color, status, CloudBandLayout.MIDDLE,
                range, camera, ticks + 19L * 400L, partial);
        sofron$prepareAuxiliary(sofron$high, color, status, CloudBandLayout.HIGH,
                range, camera, ticks + 47L * 400L, partial);
    }

    // The resource pack doubles the vertical cell size in the vertex shader.
    // Match the corresponding CPU visibility / face selection.
    @ModifyConstant(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
            constant = @Constant(floatValue = 4f, ordinal = 0))
    private float sofron$cloudThickness(float original) {
        return original * 2f;
    }

    @Redirect(method = {"render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", "renderOit"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/CloudRenderer;quadCount:I"))
    private int sofron$visibleQuads(CloudRenderer renderer) {
        if (!sofron$layered || sofron$isAuxiliary) return quadCount;
        int count = quadCount;
        if (sofron$middle != null) count = Math.max(count, sofron$state(sofron$middle).quadCount);
        if (sofron$high != null) count = Math.max(count, sofron$state(sofron$high).quadCount);
        return count;
    }

    @Unique private void sofron$drawAux(RenderPass pass, RenderPipeline pipeline, int band) {
        CloudRenderer aux = band == 1 ? sofron$middle : sofron$high;
        if (aux != null && sofron$state(aux).quadCount > 0) {
            sofron$state(aux).render(pass, pipeline);
        }
    }

    @Inject(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At("HEAD"), cancellable = true)
    private void sofron$drawFarLayers(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
        if (!sofron$layered || sofron$isAuxiliary || sofron$middle == null || sofron$high == null) return;
        int[] order = CloudBandLayout.farToNear(sofron$cameraY);
        if (quadCount == 0) {
            // Vanilla would skip its own render. The other two layers may still
            // contain visible banks of clouds and must NOT disappear.
            for (int band : order) if (band != 0) sofron$drawAux(pass, pipeline, band);
            ci.cancel();
            return;
        }
        // Draw layers farther from the camera than the base before it, to
        // preserve proper translucent back-to-front ordering in normal clouds.
        for (int band : order) {
            if (band == 0) break;
            sofron$drawAux(pass, pipeline, band);
        }
    }

    @Inject(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At("RETURN"))
    private void sofron$drawNearLayers(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
        if (!sofron$layered || sofron$isAuxiliary || sofron$middle == null || sofron$high == null
                || quadCount == 0) return;
        boolean baseDrawn = false;
        for (int band : CloudBandLayout.farToNear(sofron$cameraY)) {
            if (band == 0) { baseDrawn = true; continue; }
            if (baseDrawn) sofron$drawAux(pass, pipeline, band);
        }
    }

    @Redirect(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice sofron$skyColor(DynamicGpuData uniforms, Matrix4f matrix) {
        return sofron$layered ? uniforms.writeTransform(matrix, sofron$sky) : uniforms.writeTransform(matrix);
    }

    @Inject(method = "endFrame", at = @At("TAIL"))
    private void sofron$endFrame(CallbackInfo ci) {
        if (sofron$layered && !sofron$isAuxiliary) {
            if (sofron$middle != null) sofron$middle.endFrame();
            if (sofron$high != null) sofron$high.endFrame();
        }
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void sofron$close(CallbackInfo ci) {
        if (sofron$middle != null) {
            sofron$middle.close();
            sofron$middle = null;
        }
        if (sofron$high != null) {
            sofron$high.close();
            sofron$high = null;
        }
    }
}
