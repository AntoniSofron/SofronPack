package pl.sofron.extended.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.sofron.extended.ExtendedWorldConfig;

@Mixin(value = RenderSectionManager.class, remap = false)
abstract class ExtendedFogCullingMixin {
    @Shadow private float getRenderDistance() { throw new AssertionError(); }

    // The custom distance curve determines fog opacity in the shader. The
    // environmental end is a density input and cannot be used to remove chunks.
    @Inject(method = "getEffectiveRenderDistance", at = @At("HEAD"), cancellable = true, require = 1)
    private void sofron$keepTerrain(FogParameters fog, CallbackInfoReturnable<Float> result) {
        if (ExtendedWorldConfig.get().enabled && fog.renderEnd() == 2048f)
            result.setReturnValue(getRenderDistance());
    }
}
