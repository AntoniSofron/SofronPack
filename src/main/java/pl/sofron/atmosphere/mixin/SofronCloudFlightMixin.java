package pl.sofron.atmosphere.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import pl.sofron.atmosphere.SofronCloudAirflow;

/**
 * Modify the actual per-tick displacement, while leaving motion storage,
 * collisions, normal walking, vanilla physics and synchronization intact.
 * Runs on both the client and the authoritative integrated/dedicated server.
 */
@Mixin(Entity.class)
abstract class SofronCloudFlightMixin {
    @ModifyVariable(
            method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At("HEAD"), argsOnly = true, index = 2, require = 1)
    private Vec3 sofron$cloudWind(Vec3 movement, MoverType mover) {
        return SofronCloudAirflow.modifyMovement((Entity)(Object)this, mover, movement);
    }
}
