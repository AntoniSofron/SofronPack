package pl.sofron.atmosphere.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.sofron.atmosphere.AtmospherePresets;

@Mixin(EnvironmentAttributeSystem.Builder.class)
public abstract class AtmosphereBuilderMixin {
    @Inject(method = "addStaticLayers", at = @At("TAIL"))
    private void sofron$biomeColors(LevelAccessor level,
            CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> cir) {
        if (level instanceof ClientLevel clientLevel) {
            AtmospherePresets.addLayers((EnvironmentAttributeSystem.Builder)(Object)this, clientLevel);
        }
    }
}
