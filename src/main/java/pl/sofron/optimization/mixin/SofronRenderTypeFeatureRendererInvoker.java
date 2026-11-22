package pl.sofron.optimization.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access the protected per-layer builder without widening the vanilla API. */
@Mixin(RenderTypeFeatureRenderer.class)
public interface SofronRenderTypeFeatureRendererInvoker {
    @Invoker("getVertexBuilder")
    VertexConsumer sofron$invokeVertexBuilder(RenderType renderType);
}
