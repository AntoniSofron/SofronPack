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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
abstract class CloudLayersMixin {
    @Shadow private CloudRenderer.TextureData texture;
    @Shadow private int quadCount;
    @Shadow protected abstract void render(RenderPass pass, RenderPipeline pipeline);

    @Unique private CloudRenderer sofron$upper;
    @Unique private boolean sofron$isUpper;
    @Unique private boolean sofron$layered;
    @Unique private boolean sofron$upperFirst;
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
        if (sofron$isUpper) return;
        var client = Minecraft.getInstance();
        sofron$layered = texture != null && client.level != null && client.level.dimension().equals(Level.OVERWORLD);
        if (!sofron$layered) return;
        if (sofron$upper == null) {
            sofron$upper = new CloudRenderer();
            sofron$state(sofron$upper).sofron$isUpper = true;
        }
        var upper = sofron$state(sofron$upper);
        if (upper.texture != texture) {
            upper.texture = texture;
            sofron$upper.markForRebuild();
        }
        var sky = client.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, partial);
        sofron$sky.set(sky, -1f);
        upper.sofron$sky.set(sofron$sky);
        upper.sofron$layered = true;
        sofron$upperFirst = Math.abs(camera.y - 512) > Math.abs(camera.y - height);
        // A fixed phase offset positions the upper banks above different gaps.
        sofron$upper.prepare(color, status, 512f, range, camera, ticks + 47L * 400L, partial);
    }

    @Redirect(method = {"render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", "renderOit"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/CloudRenderer;quadCount:I"))
    private int sofron$visibleQuads(CloudRenderer renderer) {
        return sofron$layered && sofron$upper != null ? Math.max(quadCount, sofron$state(sofron$upper).quadCount) : quadCount;
    }

    @Unique private void sofron$drawUpper(RenderPass pass, RenderPipeline pipeline) {
        var upper = sofron$state(sofron$upper);
        if (upper.quadCount > 0) upper.render(pass, pipeline);
    }

    @Inject(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At("HEAD"), cancellable = true)
    private void sofron$farLayer(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
        if (!sofron$layered || sofron$upper == null) return;
        if (sofron$upperFirst || quadCount == 0) sofron$drawUpper(pass, pipeline);
        if (quadCount == 0) ci.cancel();
    }

    @Inject(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V", at = @At("RETURN"))
    private void sofron$nearLayer(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
        if (sofron$layered && sofron$upper != null && !sofron$upperFirst && quadCount > 0) sofron$drawUpper(pass, pipeline);
    }

    @Redirect(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice sofron$skyColor(DynamicGpuData uniforms, Matrix4f matrix) {
        return sofron$layered ? uniforms.writeTransform(matrix, sofron$sky) : uniforms.writeTransform(matrix);
    }

    @Inject(method = "endFrame", at = @At("TAIL"))
    private void sofron$rotate(CallbackInfo ci) {
        if (sofron$upper != null && sofron$layered) sofron$upper.endFrame();
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void sofron$close(CallbackInfo ci) {
        if (sofron$upper != null) {
            sofron$upper.close();
            sofron$upper = null;
        }
    }
}
