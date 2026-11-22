package pl.sofron.optimization.mixin;

import com.mojang.renderpearl.backend.opengl.GlConst;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ensure correct OpenGL default framebuffer at the window swap boundary. */
@Mixin(GlSurface.class)
public abstract class SofronGlSurfacePresentMixin {
    @Inject(method = "present", at = @At("HEAD"))
    private static void sofron$unbindBeforeSwap(CallbackInfo ci) {
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, 0);
    }
}
