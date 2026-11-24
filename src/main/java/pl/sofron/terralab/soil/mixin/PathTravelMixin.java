package pl.sofron.terralab.soil.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pl.sofron.terralab.travel.TravelSpeed;
import pl.sofron.terralab.soil.SoilBlocks;

@Mixin(LivingEntity.class)
abstract class PathTravelMixin {
    // Vanilla reads getSpeed here only for movement in contact with the ground.
    @ModifyExpressionValue(method = "getFrictionInfluencedSpeed", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getSpeed()F"))
    private float sofronpack$pathTravel(float speed) {
        LivingEntity mover = (LivingEntity)(Object)this;
        float multiplier;
        float altitude = 1.0F;
        if (mover instanceof Player player && !player.isPassenger() && !player.getAbilities().flying) {
            double offset = player.getY() - 60.0;
            double span = offset < 0 ? 60.0 - player.level().getMinY() : player.level().getMaxY() - 60.0;
            if (span > 0) {
                double fraction = Math.min(Math.abs(offset) / span, 1.0);
                // Ease into the neutral level and both build limits, including fractional heights.
                double gradient = fraction * fraction * (3.0 - 2.0 * fraction);
                altitude += (float)Math.copySign(0.15 * gradient, offset);
            }
            multiplier = 1.25F;
        } else if (mover instanceof AbstractHorse && mover.hasPassenger(rider -> rider instanceof Player)) {
            multiplier = 1.5F;
        } else {
            return speed;
        }

        var support = mover.getOnPos();
        var state = mover.level().getBlockState(support);
        Block ground = state.getBlock();
        if (ground == Blocks.DIRT_PATH || ground == SoilBlocks.slab(Blocks.DIRT_PATH)
                || ground == SoilBlocks.stairs(Blocks.DIRT_PATH)) return speed * multiplier * altitude;
        if (!(mover instanceof Player)) return speed;
        return speed * Math.min(2.0F, altitude * TravelSpeed.onIce(state, support.getY()));
    }
}
