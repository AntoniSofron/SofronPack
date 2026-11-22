package pl.sofron.optimization.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class SofronFastElytraMixin extends Entity {
    @Shadow protected int fallFlyTicks;
    @Shadow public abstract boolean isFallFlying();

    public SofronFastElytraMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Inject(method = "updateFallFlying()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;canGlide()Z"),
            cancellable = true)
    private void sofron$skipUnnecessaryGlidingCheck(CallbackInfo ci) {
        if (!isFallFlying() && fallFlyTicks == 0) ci.cancel();
    }
}
