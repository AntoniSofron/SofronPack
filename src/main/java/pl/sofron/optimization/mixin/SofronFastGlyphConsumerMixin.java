/*
 * Adapted for SofronPack from ImmediatelyFast 26.3:
 * https://github.com/RaphiMC/ImmediatelyFast
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 * Licensed under GNU LGPL v3 or later.
 * Source attribution and license details: THIRD_PARTY_OPTIMIZATIONS.md
 */
package pl.sofron.optimization.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Reuse the consumer when consecutive glyph quads use the same render layer. */
@Mixin(targets = "net.minecraft.client.renderer.feature.TextFeatureRenderer$GlyphRenderer")
public abstract class SofronFastGlyphConsumerMixin {
    @Unique private RenderType sofron$previousRenderType;
    @Unique private VertexConsumer sofron$previousConsumer;

    @Redirect(method = "acceptRenderable",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer sofron$cachedConsumer(TextFeatureRenderer renderer, RenderType type) {
        if (sofron$previousRenderType != type) {
            sofron$previousRenderType = type;
            sofron$previousConsumer = ((SofronRenderTypeFeatureRendererInvoker) renderer).sofron$invokeVertexBuilder(type);
        }
        return sofron$previousConsumer;
    }
}
