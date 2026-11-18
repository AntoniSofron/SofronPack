package pl.sofron.atmosphere.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.sofron.atmosphere.AtmospherePresets;

@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void sofron$earthSkyFog(FogData fog, Camera camera, ClientLevel level,
                                    float renderDistance, DeltaTracker tracker, CallbackInfo ci) {
        AtmospherePresets.applyFog(fog, camera, level, renderDistance, tracker);
    }
}
