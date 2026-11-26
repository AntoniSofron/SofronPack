package pl.sofron.extended.mixin;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Copy enough vertical context for an eight-block tint lookup without reading live world data. */
@Mixin(value = LevelSlice.class, remap = false)
public abstract class SnowLeafSliceMixin {
    @ModifyArg(method = "prepare", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;<init>(IIIIII)V"),
            index = 4, require = 0)
    private static int sofronpack$extendLeafContext(int maximumY) {
        return maximumY + 6;
    }
}
