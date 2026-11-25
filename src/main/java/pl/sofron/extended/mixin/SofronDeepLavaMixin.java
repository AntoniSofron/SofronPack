package pl.sofron.extended.mixin;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remove vanilla's continuous Y=-54 lava table only in our extended water Overworld. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class SofronDeepLavaMixin {
    @Inject(method = "createFluidPicker", at = @At("RETURN"), cancellable = true)
    private static void sofron$localLavaOnly(NoiseGeneratorSettings settings,
            CallbackInfoReturnable<Aquifer.FluidPicker> cir) {
        if (settings.noiseSettings().minY() != -128 || settings.noiseSettings().height() != 640
                || settings.seaLevel() != 63 || !settings.defaultFluid().is(Blocks.WATER)) return;
        Aquifer.FluidPicker vanilla = cir.getReturnValue();
        // A dry fallback below the old lava table, not a replacement ocean of water.
        Aquifer.FluidStatus dry = new Aquifer.FluidStatus(-128, Blocks.WATER.defaultBlockState());
        cir.setReturnValue((x, y, z) -> y < -54 ? dry : vanilla.computeFluid(x, y, z));
    }
}
