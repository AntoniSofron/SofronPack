package pl.sofron.extended.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.sofron.extended.client.ExtendedVoxelPipeline;

/** Include SofronPack's textured LOD shader in the normal resource-reload pass. */
@Mixin(RenderPipelines.class)
public abstract class ExtendedVoxelPipelineMixin {
    @Shadow private static RenderPipeline register(RenderPipeline pipeline) {
        throw new AssertionError("Mixin shadow");
    }

    @Inject(method = "registerCustomPipelines", at = @At("TAIL"), require = 0)
    private static void sofron$registerVoxelShader(CallbackInfo ci) {
        register(ExtendedVoxelPipeline.TERRAIN);
    }
}
