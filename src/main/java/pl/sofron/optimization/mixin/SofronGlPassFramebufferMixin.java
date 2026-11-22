package pl.sofron.optimization.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.renderpearl.backend.opengl.GlConst;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Avoid the unconditional framebuffer-zero binding between OpenGL passes.
 * At presentation boundaries restore the default framebuffer to prevent
 * stale-target rendering; Vulkan does not use this OpenGL-only target.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class SofronGlPassFramebufferMixin {
    @WrapWithCondition(method = "submitRenderPass",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glBindFramebuffer(II)V"))
    private boolean sofron$skipRedundantUnbind(int target, int framebuffer) {
        return false;
    }

    @Inject(method = "presentTexture", at = @At("HEAD"))
    private void sofron$unbindBeforePresent(CallbackInfo ci) {
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, 0);
    }
}
