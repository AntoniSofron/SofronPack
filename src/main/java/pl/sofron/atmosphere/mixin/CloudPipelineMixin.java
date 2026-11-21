package pl.sofron.atmosphere.mixin;

import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(RenderPipelines.class)
abstract class CloudPipelineMixin {
    // Both shader stages in both cloud pipelines must use the mod's biome-aware pair,
    // including when the original 16x resource pack is active above mod resources.
    @ModifyConstant(method = "<clinit>", constant = @Constant(stringValue = "core/clouds"), require = 4, allow = 4)
    private static String sofron$cloudShader(String original) {
        return "core/sofronpack/clouds";
    }
}
