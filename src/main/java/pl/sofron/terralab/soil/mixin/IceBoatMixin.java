package pl.sofron.terralab.soil.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import pl.sofron.terralab.soil.IceTravel;

@Mixin(AbstractBoat.class)
abstract class IceBoatMixin {
    @Shadow private AbstractBoat.Status status;

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), index = 1)
    private Vec3 sofronpack$iceTravel(Vec3 movement) {
        AbstractBoat boat = (AbstractBoat)(Object)this;
        if (status != AbstractBoat.Status.ON_LAND || !(boat.getControllingPassenger() instanceof Player)) return movement;
        var support = boat.getOnPos();
        if (support.getY() < 70) return movement;
        float factor = IceTravel.multiplier(boat.level().getBlockState(support), support.getY());
        return factor == 1.0F ? movement : movement.multiply(factor, 1.0, factor);
    }
}
