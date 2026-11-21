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

@Mixin(CloudRenderer.class)
abstract class CloudLayersMixin {
    @Shadow private CloudRenderer.TextureData texture;
    @Shadow private int quadCount;
    @Shadow protected abstract void render(RenderPass pass, RenderPipeline pipeline);

    @Unique private CloudRenderer[] sofron$extra;
    @Unique private int sofron$farIndex;
    @Unique private double sofron$cameraY;
    @Unique private float sofron$baseHeight;
    @Unique private boolean sofron$isExtra;
    @Unique private boolean sofron$layered;
    @Unique private int sofron$range = -1;
    @Unique private final Vector4f sofron$sky = new Vector4f();

    @Unique private static CloudLayersMixin sofron$state(CloudRenderer renderer) {
        return (CloudLayersMixin)(Object)renderer;
    }

    @Inject(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"))
    private void sofron$prepare(int color, CloudStatus status, float height, int range, Vec3 camera,
                                long ticks, float partial, CallbackInfo ci) {
        if (range != sofron$range) {
            ((CloudRenderer)(Object)this).markForRebuild();
            sofron$range = range;
        }
        if (sofron$isExtra) return;
        var client = Minecraft.getInstance();
        sofron$layered = texture != null && client.level != null && client.level.dimension().equals(Level.OVERWORLD);
        if (!sofron$layered) return;
        if (sofron$extra == null) {
            sofron$extra = new CloudRenderer[]{new CloudRenderer(), new CloudRenderer()};
            for (var renderer : sofron$extra) sofron$state(renderer).sofron$isExtra = true;
        }
        var sky = client.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, partial);
        sofron$sky.set(sky, -1f);
        sofron$baseHeight = height;
        sofron$cameraY = camera.y;
        sofron$farIndex = sofron$distance(1) > sofron$distance(0) ? 1 : 0;
        for (int i = 0; i < sofron$extra.length; i++) {
            CloudRenderer renderer = sofron$extra[i];
            var layer = sofron$state(renderer);
            if (layer.texture != texture) {
                layer.texture = texture;
                renderer.markForRebuild();
            }
            layer.sofron$sky.set(sofron$sky);
            layer.sofron$layered = true;
            renderer.prepare(color, status, sofron$height(i), range, camera,
                    ticks + (i == 0 ? 23L : 47L) * 400L, partial);
        }
    }

    // The pack doubles vertical cell size in the vertex shader. Match CPU face selection.
    @ModifyConstant(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
            constant = @Constant(floatValue = 4f, ordinal = 0))
    private float sofron$cloudThickness(float original) {
        return original * 2f;
    }

    @Redirect(method = {"render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", "renderOit"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/CloudRenderer;quadCount:I"))
    private int sofron$visibleQuads(CloudRenderer renderer) {
        if (sofron$layered && sofron$extra != null && quadCount == 0) {
            for (var layer : sofron$extra) if (sofron$state(layer).quadCount > 0) return 1;
        }
        return quadCount;
    }

    @Unique private static float sofron$height(int index) {
        return index == 0 ? 384f : 512f;
    }

    @Unique private double sofron$distance(int index) {
        return Math.abs(sofron$cameraY - (sofron$height(index) + 4));
    }

    @Unique private void sofron$drawExtras(RenderPass pass, RenderPipeline pipeline, boolean beforeBase) {
        double baseDistance = Math.abs(sofron$cameraY - (sofron$baseHeight + 4));
        for (int order = 0; order < 2; order++) {
            int index = order == 0 ? sofron$farIndex : 1 - sofron$farIndex;
            if ((quadCount == 0 || sofron$distance(index) >= baseDistance) != beforeBase) continue;
            var layer = sofron$state(sofron$extra[index]);
            if (layer.quadCount > 0) layer.render(pass, pipeline);
        }
    }

    @Inject(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At("HEAD"), cancellable = true)
    private void sofron$farLayer(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
        if (!sofron$layered || sofron$extra == null) return;
        sofron$drawExtras(pass, pipeline, true);
        if (quadCount == 0) ci.cancel();
    }

    @Inject(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V", at = @At("RETURN"))
    private void sofron$nearLayer(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
        if (sofron$layered && sofron$extra != null && quadCount > 0) sofron$drawExtras(pass, pipeline, false);
    }

    @Redirect(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice sofron$skyColor(DynamicGpuData uniforms, Matrix4f matrix) {
        return sofron$layered ? uniforms.writeTransform(matrix, sofron$sky) : uniforms.writeTransform(matrix);
    }

    @Inject(method = "endFrame", at = @At("TAIL"))
    private void sofron$rotate(CallbackInfo ci) {
        if (sofron$extra != null && sofron$layered)
            for (var layer : sofron$extra) layer.endFrame();
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void sofron$close(CallbackInfo ci) {
        if (sofron$extra != null) {
            for (var layer : sofron$extra) layer.close();
            sofron$extra = null;
        }
    }
}
