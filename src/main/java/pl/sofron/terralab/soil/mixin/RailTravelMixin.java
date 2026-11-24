package pl.sofron.terralab.soil.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.vehicle.minecart.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import pl.sofron.terralab.soil.RailTravel;

@Mixin(OldMinecartBehavior.class)
abstract class RailTravelMixin extends MinecartBehavior {
    protected RailTravelMixin(AbstractMinecart cart) { super(cart); }

    @ModifyArg(method = "moveAlongTrack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), index = 1)
    private Vec3 sofronpack$railDistance(Vec3 movement) {
        double factor = RailTravel.distanceMultiplier(minecart);
        return factor == 1.0 ? movement : movement.scale(factor);
    }
}

@Mixin(NewMinecartBehavior.class)
abstract class ExperimentalRailTravelMixin extends MinecartBehavior {
    protected ExperimentalRailTravelMixin(AbstractMinecart cart) { super(cart); }

    // Scale the distance budget and its speed corrections, leaving stored momentum alone.
    @ModifyExpressionValue(method = "moveAlongTrack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;horizontalDistance()D"))
    private double sofronpack$railDistance(double distance) {
        return distance * RailTravel.distanceMultiplier(minecart);
    }
}
